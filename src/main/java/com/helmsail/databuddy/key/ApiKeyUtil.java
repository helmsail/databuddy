package com.helmsail.databuddy.key;

import java.security.SecureRandom;

/**
 * API Key 生成与脱敏工具类
 */
public final class ApiKeyUtil {

	/** Key 前缀 */
	private static final String PREFIX = "sk-";

	/** 随机字符集 */
	private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

	/** 随机部分长度 */
	private static final int LENGTH = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private ApiKeyUtil() {
	}

	/** 生成 API Key:sk- 前缀 + 32 位随机字符 */
	public static String generate() {
		StringBuilder builder = new StringBuilder(PREFIX);
		for (int i = 0; i < LENGTH; i++) {
			builder.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
		}
		return builder.toString();
	}

	/** 脱敏 API Key:仅保留末尾 4 位,如 ****A1b2 */
	public static String mask(String apiKey) {
		if (apiKey == null || apiKey.length() <= 8) {
			return "****";
		}
		return "****" + apiKey.substring(apiKey.length() - 4);
	}

}
