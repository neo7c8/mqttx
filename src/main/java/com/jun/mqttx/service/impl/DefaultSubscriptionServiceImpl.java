package com.jun.mqttx.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jun.mqttx.config.MqttxConfig;
import com.jun.mqttx.constants.InternalMessageEnum;
import com.jun.mqttx.consumer.Watcher;
import com.jun.mqttx.entity.ClientSub;
import com.jun.mqttx.entity.ClientSubOrUnsubMsg;
import com.jun.mqttx.entity.InternalMessage;
import com.jun.mqttx.entity.Tuple2;
import com.jun.mqttx.service.IInternalMessagePublishService;
import com.jun.mqttx.service.ISubscriptionService;
import com.jun.mqttx.utils.JsonSerializer;
import com.jun.mqttx.utils.Serializer;
import com.jun.mqttx.utils.TopicUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主题订阅服务.
 * 为了优化 cleanSession = 1 会话的性能，所有与之相关的状态均保存在内存当中.
 *
 * @author Jun
 * @since 1.0.4
 */
@Slf4j
@Service
public class DefaultSubscriptionServiceImpl implements ISubscriptionService, Watcher {

    //@formatter:off

    private static final int ASSUME_COUNT = 100_000;
    /** 按顺序 -> 订阅，解除订阅，删除 topic */
    private static final int SUB = 1, UN_SUB = 2, DEL_TOPIC = 3;
    private final ReactiveStringRedisTemplate stringRedisTemplate;
    private final Serializer serializer;
    private final IInternalMessagePublishService internalMessagePublishService;
    /** client订阅主题, 订阅主题前缀, 主题集合 */
    private final String clientTopicsPrefix, topicSetKey, topicPrefix;
    private final boolean enableInnerCache, enableCluster;
    private final int brokerId;


    /*                                              cleanSession = 1                                                   */

    /** cleanSession = 1 的主题集合，存储于内存中 */
    private final Set<String> inMemTopics = ConcurrentHashMap.newKeySet(ASSUME_COUNT);
    /** cleanSession = 0 的主 topic -> clients 关系集合 */
    private final Map<String, ConcurrentHashMap.KeySetView<ClientSub,Boolean>> inMemTopicClientsMap = new ConcurrentHashMap<>(ASSUME_COUNT);
    /** cleanSession = 0 的主 client -> topics 关系集合 */
    private final Map<String, ConcurrentHashMap.KeySetView<String,Boolean>> inMemClientTopicsMap = new ConcurrentHashMap<>(ASSUME_COUNT);

    /*                                              cleanSession = 0                                                   */

    /** cleanSession = 0 的主题集合。内部缓存，{@link this#enableInnerCache} == true 时使用 */
    private final Set<String> inDiskTopics = ConcurrentHashMap.newKeySet(ASSUME_COUNT);
    /** cleanSession = 0 的主 topic -> clients 关系集合 */
    private final Map<String, ConcurrentHashMap.KeySetView<ClientSub, Boolean>> inDiskTopicClientsMap = new ConcurrentHashMap<>(ASSUME_COUNT);

    /*                                               系统主题                                                                  */
    /** 系统主题 -> clients map */
    private final Map<String, ConcurrentHashMap.KeySetView<ClientSub, Boolean>> sysTopicClientsMap = new ConcurrentHashMap<>();

    //@formatter:on

    public DefaultSubscriptionServiceImpl(ReactiveStringRedisTemplate stringRedisTemplate, MqttxConfig mqttxConfig, Serializer serializer,
                                          @Nullable IInternalMessagePublishService internalMessagePublishService) {
        Assert.notNull(stringRedisTemplate, "stringRedisTemplate can't be null");

        this.stringRedisTemplate = stringRedisTemplate;
        this.serializer = serializer;
        this.internalMessagePublishService = internalMessagePublishService;
        this.clientTopicsPrefix = mqttxConfig.getRedis().getClientTopicSetPrefix();
        this.topicPrefix = mqttxConfig.getRedis().getTopicPrefix();
        this.topicSetKey = mqttxConfig.getRedis().getTopicSetKey();

        MqttxConfig.Cluster cluster = mqttxConfig.getCluster();
        this.enableCluster = cluster.getEnable();
        this.enableInnerCache = mqttxConfig.getEnableInnerCache();
        if (enableInnerCache) {
            // 非测试模式，初始化缓存
            initInnerCache(stringRedisTemplate);
        }
        this.brokerId = mqttxConfig.getBrokerId();

        Assert.hasText(this.topicPrefix, "topicPrefix can't be null");
        Assert.hasText(this.topicSetKey, "topicSetKey can't be null");
    }

    /**
     * 订阅主题
     *
     * @param clientSub 客户订阅信息
     */
    @Override
    public Mono<Void> subscribe(ClientSub clientSub) {
        String topic = clientSub.getTopic();
        String clientId = clientSub.getClientId();
        int qos = clientSub.getQos();
        boolean cleanSession = clientSub.isCleanSession();

        // 保存订阅关系
        // 1. 保存 topic -> client 映射
        // 2. 将topic保存到redis set 集合中
        // 3. 保存 client -> topics
        if (cleanSession) {
            inMemTopicClientsMap
                    .computeIfAbsent(topic, s -> ConcurrentHashMap.newKeySet())
                    .add(clientSub);
            inMemTopics.add(topic);
            inMemClientTopicsMap
                    .computeIfAbsent(clientId, s -> ConcurrentHashMap.newKeySet())
                    .add(topic);

            if (enableCluster) {
                InternalMessage<ClientSubOrUnsubMsg> im = new InternalMessage<>(
                        new ClientSubOrUnsubMsg(clientId, qos, topic, cleanSession, null, SUB),
                        System.currentTimeMillis(),
                        brokerId
                );
                internalMessagePublishService.publish(im, InternalMessageEnum.SUB_UNSUB.getChannel());
            }
            return Mono.empty();
        } else {
            return Mono.when(
                    stringRedisTemplate.opsForHash().put(topicPrefix + topic, clientId, String.valueOf(qos)),
                    stringRedisTemplate.opsForSet().add(topicSetKey, topic),
                    stringRedisTemplate.opsForSet().add(clientTopicsPrefix + clientId, topic)
            ).then(Mono.fromRunnable(() -> {
                if (enableInnerCache) {
                    subscribeWithCache(clientSub);
                }

                if (enableCluster) {
                    InternalMessage<ClientSubOrUnsubMsg> im = new InternalMessage<>(
                            new ClientSubOrUnsubMsg(clientId, qos, topic, false, null, SUB),
                            System.currentTimeMillis(),
                            brokerId
                    );
                    internalMessagePublishService.publish(im, InternalMessageEnum.SUB_UNSUB.getChannel());
                }
            }));
        }
    }

    /**
     * 解除订阅
     *
     * @param clientId     客户id
     * @param cleanSession clientId 关联会话 cleanSession 状态
     * @param topics       主题列表
     */
    @Override
    public Mono<Void> unsubscribe(String clientId, boolean cleanSession, List<String> topics) {
        if (CollectionUtils.isEmpty(topics)) {
            return Mono.empty();
        }

        if (cleanSession) {
            topics.forEach(topic -> {
                ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = inMemTopicClientsMap.get(topic);
                if (!CollectionUtils.isEmpty(clientSubs)) {
                    clientSubs.remove(ClientSub.of(clientId, 0, topic, false));
                }
            });
            Optional.ofNullable(inMemClientTopicsMap.get(clientId)).ifPresent(t -> t.removeAll(topics));

            // 集群广播
            if (enableCluster) {
                ClientSubOrUnsubMsg clientSubOrUnsubMsg = new ClientSubOrUnsubMsg(clientId, 0, null, cleanSession, topics, UN_SUB);
                InternalMessage<ClientSubOrUnsubMsg> im = new InternalMessage<>(clientSubOrUnsubMsg, System.currentTimeMillis(), brokerId);
                internalMessagePublishService.publish(im, InternalMessageEnum.SUB_UNSUB.getChannel());
            }
            return Mono.empty();
        } else {
            List<Mono<Long>> monos = topics.stream()
                    .map(e -> stringRedisTemplate.opsForHash().remove(topicPrefix + e, clientId))
                    .toList();
            return Mono.when(monos)
                    .then(stringRedisTemplate.opsForSet().remove(clientTopicsPrefix + clientId, topics).then())
                    .doOnSuccess(unused -> {
                        if (enableInnerCache) {
                            unsubscribeWithCache(clientId, topics);
                        }

                        // 集群广播
                        if (enableCluster) {
                            ClientSubOrUnsubMsg clientSubOrUnsubMsg = new ClientSubOrUnsubMsg(clientId, 0, null, cleanSession, topics, UN_SUB);
                            InternalMessage<ClientSubOrUnsubMsg> im = new InternalMessage<>(clientSubOrUnsubMsg, System.currentTimeMillis(), brokerId);
                            internalMessagePublishService.publish(im, InternalMessageEnum.SUB_UNSUB.getChannel());
                        }
                    });
        }
    }


    /**
     * 返回订阅主题的客户列表。考虑到 pub 类别的消息最为频繁且每次 pub 都会触发 <code>searchSubscribeClientList(String topic)</code>
     * 方法，所以增加内部缓存以优化该方法的执行逻辑。
     *
     * @param topic 主题
     * @return 客户ID列表
     */
    @Override
    public Flux<ClientSub> searchSubscribeClientList(String topic) {
        // 启用内部缓存机制
        if (enableInnerCache) {
            return Flux.fromIterable(searchSubscribeClientListByCache(topic));
        }

        // 未启用内部缓存机制，直接通过 redis 抓取
        List<ClientSub> clientSubList = new ArrayList<>();

        // cleanSession 的主题
        inMemTopics.stream()
                .filter(e -> TopicUtils.match(topic, e))
                .forEach(e -> {
                    ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = inMemTopicClientsMap.get(e);
                    if (!CollectionUtils.isEmpty(clientSubs)) {
                        clientSubList.addAll(clientSubs);
                    }
                });
        return stringRedisTemplate.opsForSet().members(topicSetKey)
                .filter(t -> TopicUtils.match(topic, t))
                .flatMap(t -> stringRedisTemplate.opsForHash().entries(topicPrefix + t)
                        .map(entry -> {
                            String clientId = (String) entry.getKey();
                            String qosStr = (String) entry.getValue();
                            return ClientSub.of(clientId, Integer.parseInt(qosStr), t, false);
                        })
                ).concatWith(Flux.fromIterable(clientSubList));
    }

    @Override
    public Mono<Void> clearClientSubscriptions(String clientId, boolean cleanSession) {
        Set<String> keys;
        if (cleanSession) {
            keys = inMemClientTopicsMap.remove(clientId);
            if (CollectionUtils.isEmpty(keys)) {
                return Mono.empty();
            }
            return unsubscribe(clientId, cleanSession, new ArrayList<>(keys));
        } else {
            return stringRedisTemplate.opsForSet().members(clientTopicsPrefix + clientId)
                    .collectList()
                    .flatMap(e -> stringRedisTemplate.delete(clientTopicsPrefix + clientId)
                            .flatMap(unused -> unsubscribe(clientId, cleanSession, new ArrayList<>(e)))
                    );
        }
    }

    @Override
    public Mono<Void> clearUnAuthorizedClientSub(String clientId, List<String> authorizedSub) {
        List<String> collect = inDiskTopics
                .stream()
                .filter(topic -> !authorizedSub.contains(topic))
                .collect(Collectors.toList());
        return Mono.when(unsubscribe(clientId, false, collect), unsubscribe(clientId, true, collect));
    }


    @Override
    public void action(byte[] msg) {
        InternalMessage<ClientSubOrUnsubMsg> im;
        if (serializer instanceof JsonSerializer) {
            im = ((JsonSerializer) serializer).deserialize(msg, new TypeReference<>() {
            });
        } else {
            //noinspection unchecked
            im = serializer.deserialize(msg, InternalMessage.class);
        }
        ClientSubOrUnsubMsg data = im.getData();
        final int type = data.getType();
        final String clientId = data.getClientId();
        final String topic = data.getTopic();
        final boolean cleanSession = data.isCleanSession();
        switch (type) {
            case SUB -> {
                ClientSub clientSub = ClientSub.of(clientId, data.getQos(), topic, cleanSession);
                if (cleanSession) {
                    inMemTopicClientsMap
                            .computeIfAbsent(topic, s -> ConcurrentHashMap.newKeySet())
                            .add(clientSub);
                    inMemTopics.add(topic);
                    inMemClientTopicsMap
                            .computeIfAbsent(clientId, s -> ConcurrentHashMap.newKeySet())
                            .add(topic);
                } else {
                    if (enableInnerCache) {
                        subscribeWithCache(ClientSub.of(clientId, data.getQos(), topic, false));
                    }
                }
            }
            case UN_SUB -> {
                if (data.isCleanSession()) {
                    data.getTopics().forEach(t -> {
                        ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = inMemTopicClientsMap.get(t);
                        if (!CollectionUtils.isEmpty(clientSubs)) {
                            clientSubs.remove(ClientSub.of(clientId, 0, t, false));
                        }
                    });
                    Optional.ofNullable(inMemClientTopicsMap.get(clientId)).ifPresent(t -> t.removeAll(data.getTopics()));
                } else {
                    if (enableInnerCache) {
                        unsubscribeWithCache(clientId, data.getTopics());
                    }
                }
            }
            case DEL_TOPIC -> {
                // 移除内存中的数据
                inMemTopics.remove(topic);
                Optional.ofNullable(inMemTopicClientsMap.remove(topic))
                        .ifPresent(e -> e.forEach(
                                clientSub -> Optional.
                                        ofNullable(inMemClientTopicsMap.get(clientId))
                                        .ifPresent(t -> t.remove(topic)))
                        );
                stringRedisTemplate.opsForSet().remove(topicSetKey, topic)
                        .doOnSuccess(l -> {
                            // 移除缓存中的数据
                            if (enableInnerCache) {
                                removeTopicWithCache(topic);
                            }
                        })
                        .subscribe(e -> {
                        }, e -> log.error(e.getMessage(), e));
            }
            default -> log.error("非法的 ClientSubOrUnsubMsg type:" + type);
        }
    }

    @Override
    public boolean support(String channel) {
        return InternalMessageEnum.SUB_UNSUB.getChannel().equals(channel);
    }

    /**
     * 初始化内部缓存。目前的策略是全部加载，其实可以按需加载，按业务需求来吧。
     */
    private void initInnerCache(final ReactiveStringRedisTemplate redisTemplate) {
        log.info("enableInnerCache=true, 开始加载缓存...");

        redisTemplate.opsForSet().members(topicSetKey)
                .collectList()
                .doOnSuccess(inDiskTopics::addAll)
                .flatMapIterable(Function.identity())
                .flatMap(topic -> redisTemplate.opsForHash().entries(topicPrefix + topic).map(e -> new Tuple2<>(topic, e)))
                .doOnNext(e -> {
                    String topic = e.t0();
                    String k = (String) e.t1().getKey();
                    String v = (String) e.t1().getValue();
                    inDiskTopicClientsMap.computeIfAbsent(topic, s -> ConcurrentHashMap.newKeySet())
                            .add(ClientSub.of(k, Integer.parseInt(v), topic, false));
                })
                .then()
                .doOnError(t -> log.error(t.getMessage(), t))
                // 这里我们应该阻塞
                .block();
    }

    /**
     * 通过缓存获取客户端订阅列表
     *
     * @param topic 主题
     * @return 客户端订阅列表
     */
    private List<ClientSub> searchSubscribeClientListByCache(String topic) {
        // result
        List<ClientSub> clientSubList = new ArrayList<>();

        for (String t : inDiskTopics) {
            if (TopicUtils.match(topic, t)) {
                ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = inDiskTopicClientsMap.get(t);
                if (!CollectionUtils.isEmpty(clientSubs)) {
                    clientSubList.addAll(clientSubs);
                }
            }
        }
        for (String t : inMemTopics) {
            if (TopicUtils.match(topic, t)) {
                ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = inMemTopicClientsMap.get(t);
                if (!CollectionUtils.isEmpty(clientSubs)) {
                    clientSubList.addAll(clientSubs);
                }
            }
        }

        return clientSubList;
    }

    /**
     * 移除 topic 缓存
     *
     * @param topic 主题
     */
    private void removeTopicWithCache(String topic) {
        inDiskTopics.remove(topic);
        inDiskTopicClientsMap.remove(topic);
    }

    /**
     * 移除缓存中的订阅
     *
     * @param clientId 客户端ID
     * @param topics   主题列表
     */
    private void unsubscribeWithCache(String clientId, List<String> topics) {
        for (String topic : topics) {
            ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = inDiskTopicClientsMap.get(topic);
            if (clientSubs != null) {
                clientSubs.remove(ClientSub.of(clientId, 0, topic, false));
            }
        }
    }

    /**
     * 将客户端订阅存储到缓存
     *
     * @param clientSub 客户端端订阅
     */
    private void subscribeWithCache(ClientSub clientSub) {
        String topic = clientSub.getTopic();

        inDiskTopics.add(topic);

        // 保存客户端订阅内容
        inDiskTopicClientsMap
                .computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet())
                .add(clientSub);
    }

    @Override
    public Flux<ClientSub> searchSysTopicClients(String topic) {
        // result
        List<ClientSub> clientSubList = new ArrayList<>();

        sysTopicClientsMap.forEach((wildTopic, set) -> {
            if (TopicUtils.match(topic, wildTopic)) {
                clientSubList.addAll(set);
            }
        });

        return Flux.fromIterable(clientSubList);
    }

    @Override
    public Mono<Void> subscribeSys(ClientSub clientSub) {
        sysTopicClientsMap.computeIfAbsent(clientSub.getTopic(), k -> ConcurrentHashMap.newKeySet()).add(clientSub);
        return Mono.empty();
    }

    @Override
    public Mono<Void> unsubscribeSys(String clientId, List<String> topics) {
        for (String topic : topics) {
            ConcurrentHashMap.KeySetView<ClientSub, Boolean> clientSubs = sysTopicClientsMap.get(topic);
            if (!CollectionUtils.isEmpty(clientSubs)) {
                clientSubs.remove(ClientSub.of(clientId, 0, topic, false));
            }
        }

        return Mono.empty();
    }

    @Override
    public Mono<Void> clearClientSysSub(String clientId) {
        sysTopicClientsMap.forEach((topic, clientSubs) -> clientSubs.remove(ClientSub.of(clientId, 0, topic, false)));
        return Mono.empty();
    }
}
