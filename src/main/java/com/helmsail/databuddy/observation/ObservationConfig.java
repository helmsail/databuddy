package com.helmsail.databuddy.observation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

/**
 * 可观测性配置:注册官方注解切面,使被 @Observed 标记的方法自动成为一个 span。
 * 不写注解 = 不埋点(切面只对带注解的方法生效),埋点位置一览无余
 */
@Configuration
public class ObservationConfig {

	@Bean
	ObservedAspect observedAspect(ObservationRegistry registry) {
		return new ObservedAspect(registry);
	}

}
