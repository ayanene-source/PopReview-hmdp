package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;


//定义拦截器步骤：1，创建类继承HandlerInterceptor 2. 实现方法 3. 配置拦截器
public class RefreshLoginInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;

    public RefreshLoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    // 在业务处理器处理请求之前被调用
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
         //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (token == null) {
            return true;
        }
         //2.基于token从redis中获取用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
        if (userMap == null || userMap.isEmpty()) {
            return true;
        }
        //4.将取出来的用户数据Hash转化为UserDTO类型
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);

        // 5.保存用户到ThreadLocal
        UserHolder.saveUser((UserDTO) userDTO);

        //更新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清空ThreadLocal的用户
        UserHolder.removeUser();
    }
}

