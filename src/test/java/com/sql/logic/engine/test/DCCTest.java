package com.sql.logic.engine.test;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import com.sql.logic.engine.common.DCC.domain.model.valobj.AttributeVO;
import com.sql.logic.engine.common.DCC.types.Annotations.DCCValue;
import com.sql.logic.engine.test.DCCTest.DCCTestUser;

import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class DCCTest {
    
    @DCCValue("downgradeSwitch:0")
    private String downgradeSwitch;

    @Autowired
    private RTopic dynamicConfigCenterRedisTopic;

    @Test
    public void test_get() throws InterruptedException {
        log.info("测试结果:{}", downgradeSwitch);
    }

    @Test
    public void test_publish() throws InterruptedException {
        // push
        dynamicConfigCenterRedisTopic.publish(new AttributeVO("downgradeSwitch", "4"));
        // new CountDownLatch(1).await();
    }

    /** ------------- Complex Object DCC Test ------------------------ */

    @Data
    public class DCCTestUser implements Serializable {
        private Long id;
        private String username;
        private Integer age;
    }

    @DCCValue("dccUser:{}")
    private String dccUser;

    @Test
    public void test_user_get() throws InterruptedException {
       log.info("测试结果:{}", dccUser);
       DCCTestUser user = JSONUtil.toBean(dccUser, DCCTestUser.class);
       log.info("测试bean:{}", user);
    }

    @Test
    public void test_user_publish() throws InterruptedException {
        DCCTestUser user = new DCCTestUser();
        user.setId(1001L);
        user.setUsername("atom");
        user.setAge(24);
        String json = JSONUtil.toJsonStr(user);
        dynamicConfigCenterRedisTopic.publish(new AttributeVO("dccUser", json));
        log.info("已发布对象(JSON):{}", json);
        // new CountDownLatch(1).await();
    }

}
