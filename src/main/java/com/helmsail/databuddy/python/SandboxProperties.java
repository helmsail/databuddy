package com.helmsail.databuddy.python;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * python 沙箱配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "databuddy.python")
public class SandboxProperties {

	/** 沙箱镜像(预装 numpy/pandas/matplotlib 与中文字体,见 docker/python/Dockerfile) */
	private String image;

	/** 常驻容器数(启动后异步预热) */
	private int minIdle;

	/** 容器总数上限 */
	private int maxTotal;

	/** 空闲收缩时间:空闲超过该值的容器销毁,回落到常驻数 */
	private Duration idleTtl;

	/** 借容器等待上限,超时视为容量耗尽 */
	private Duration borrowTimeout;

	/** 单次执行超时,超时的容器直接销毁(状态不可信) */
	private Duration execTimeout;

	/** 沙箱工作目录根(宿主侧,挂载进容器 /work) */
	private String workRoot;

}
