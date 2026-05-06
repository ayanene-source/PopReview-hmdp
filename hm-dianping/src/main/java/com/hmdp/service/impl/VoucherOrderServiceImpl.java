package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String STREAM_ORDERS_KEY = "stream.orders";
    private static final String ORDER_CONSUMER_GROUP = "g1";
    private static final String ORDER_CONSUMER_NAME = "c1";

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ApplicationContext applicationContext;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private volatile boolean running = true;

    /**
     * 初始化方法：确保Redis Stream及消费者组存在，并启动异步线程处理订单消息
     */
    @PostConstruct
    private void init() {
        // 确保Stream和消费者组已创建
        ensureOrderStreamAndGroup();
        // 提交异步任务，持续监听并处理订单消息
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 确保Redis Stream键存在，并创建消费者组
     * 如果Stream不存在则初始化一条消息以创建Stream
     * 如果消费者组已存在则忽略异常，否则抛出异常
     */
    private void ensureOrderStreamAndGroup() {
        // 检查Stream是否存在，若不存在则添加一条初始消息以创建Stream
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(STREAM_ORDERS_KEY))) {
            stringRedisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(Collections.singletonMap("init", "0"))
                            .withStreamKey(STREAM_ORDERS_KEY)
            );
        }
        try {
            // 尝试创建消费者组
            stringRedisTemplate.opsForStream().createGroup(STREAM_ORDERS_KEY, ORDER_CONSUMER_GROUP);
        } catch (DataAccessException e) {
            String message = e.getMessage();
            // 如果异常信息包含BUSYGROUP，说明消费者组已存在，属于正常情况，记录日志后返回
            if (message != null && message.contains("BUSYGROUP")) {
                log.info("消费者组已存在: {}", message);
                return;
            }
            // 其他异常则向上抛出
            throw e;
        }
    }

    /**
     * 销毁方法：停止订单处理线程，优雅关闭线程池
     */
    @PreDestroy
    private void destroy() {
        // 标记运行状态为false，使监听线程退出循环
        running = false;
        // 启动线程池关闭流程，不再接受新任务
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            // 等待5秒让正在执行的任务完成
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                // 如果超时仍未结束，则强制关闭线程池
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            // 如果等待过程中被中断，则强制关闭线程池
            SECKILL_ORDER_EXECUTOR.shutdownNow();
            // 恢复中断状态
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 内部类：订单消息处理线程
     * 负责从 Redis Stream 中持续读取订单消息并处理
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    // 1. 从 Stream 中读取消息（阻塞式，最多等待2秒）
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_CONSUMER_GROUP, ORDER_CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    // 如果没有读取到消息，继续下一次循环
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    // 2. 获取第一条消息
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    // 3. 将消息内容转换为 VoucherOrder 对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 处理订单业务逻辑
                    handleVoucherOrder(voucherOrder);
                    // 5. 确认消息已处理（ACK），防止消息重复消费
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, ORDER_CONSUMER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理订单消息异常", e);
                    // 发生异常时，尝试处理 Pending List 中的遗留消息
                    handlePendingList();
                }
            }
        }

        /**
         * 处理 Pending List 中的消息
         * 当正常消费出现异常时，调用此方法处理未确认的消息，确保消息不丢失
         */
        private void handlePendingList() {
            while (running) {
                try {
                    // 1. 从 Pending List 中读取消息（从 ID "0" 开始读取，即所有未 ACK 的消息）
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_CONSUMER_GROUP, ORDER_CONSUMER_NAME),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                    );
                    // 如果 Pending List 为空，说明没有遗留消息，退出循环
                    if (records == null || records.isEmpty()) {
                        break;
                    }
                    // 2. 获取第一条消息
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    // 3. 将消息内容转换为 VoucherOrder 对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 处理订单业务逻辑
                    handleVoucherOrder(voucherOrder);
                    // 5. 确认消息已处理（ACK）
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, ORDER_CONSUMER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理 pending-list 订单异常", e);
                    // 如果处理再次失败，休眠 50ms 后重试，避免频繁报错占用 CPU
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        // 响应中断信号，退出循环
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    /**
     * 处理 voucher 订单创建逻辑
     * 使用 Redisson 分布式锁确保同一用户并发下单时的线程安全，
     * 并通过 Spring AOP 代理调用 createVoucherOrder 以保障事务生效。
     *
     * @param voucherOrder 待处理的优惠券订单对象
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取当前用户ID
        Long userId = voucherOrder.getUserId();
        // 构建基于用户ID的分布式锁键，确保同一用户只能有一个下单操作在执行
        RLock redisLock = redissonClient.getLock("order:" + userId);
        // 尝试获取锁，不等待，立即返回结果
        boolean isLock = redisLock.tryLock();
        // 如果获取锁失败，说明该用户正在处理其他下单请求，直接返回并记录日志
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 从Spring容器中获取当前Service的代理对象，以确保@Transactional注解生效
            IVoucherOrderService proxy = applicationContext.getBean(IVoucherOrderService.class);
            // 通过代理对象调用创建订单方法，执行数据库操作
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 无论业务执行成功与否，最终都要释放分布式锁，避免死锁
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");//生成订单id（全局唯一ID）
        //执行Lua脚本（完成用户权限判断，防超卖防多单，用户信息写入redis，下单数据保存到stream消息队列）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        return Result.ok(orderId);
    }

    /**
     * 创建优惠券订单
     * 该方法被@Transactional注解修饰，确保在同一个事务中执行数据库操作。
     * 主要逻辑包括：校验用户是否重复下单、扣减库存、保存订单记录。
     *
     * @param voucherOrder 包含用户ID和优惠券ID的订单对象
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户ID和优惠券ID
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 2. 校验一人一单：查询当前用户是否已经购买过该优惠券
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            // 如果已存在订单记录，说明用户重复下单，记录错误日志并返回
            log.error("用户已经抢购过一次");
            return;
        }

        // 3. 扣减库存（乐观锁）：使用CAS机制（Compare And Swap）确保库存充足且原子性扣减
        // setSql("stock = stock - 1") 实现库存自减
        // gt("stock", 0) 确保库存大于0时才更新，防止超卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        
        // 4. 判断扣减结果
        if (!success) {
            // 如果更新失败，说明库存不足或优惠券不存在，记录错误日志并返回
            log.error("库存不足");
            return;
        }

        // 5. 保存订单信息到数据库
        save(voucherOrder);
    }
}
