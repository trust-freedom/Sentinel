package com.alibaba.csp.sentinel.demo.spring.webmvc.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.stereotype.Service;

@Service
public class WebMvcTestService {

    public void testService(){
        Entry entry = null;
        try {
            entry = SphU.entry("testService");
        } catch (BlockException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }

    }

}
