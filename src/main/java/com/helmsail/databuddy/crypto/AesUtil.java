package com.helmsail.databuddy.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 加解密工具类,加密结果为 Base64(IV + 密文),自带完整性校验
 */
public final class AesUtil {

	/** 算法/模式/填充 */
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	/** 算法名称 */
	private static final String ALGORITHM = "AES";

	/** 密钥长度(字节) */
	private static final int KEY_LENGTH = 32;

	/** IV 长度(字节) */
	private static final int IV_LENGTH = 12;

	/** 认证标签长度(位) */
	private static final int TAG_LENGTH = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private AesUtil() {
	}

	/** 生成一个 Base64 编码的 32 字节随机密钥,用于配置到 application.yml */
	public static String generateKey() {
		byte[] key = new byte[KEY_LENGTH];
		RANDOM.nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

	/**
	 * 加密
	 * @param plainText 明文
	 * @param key Base64 编码的 32 字节密钥
	 * @return Base64(IV + 密文)
	 */
	public static String encrypt(String plainText, String key) {
		try {
			byte[] iv = new byte[IV_LENGTH];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, toSecretKey(key), new GCMParameterSpec(TAG_LENGTH, iv));
			byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
			byte[] result = new byte[iv.length + cipherText.length];
			System.arraycopy(iv, 0, result, 0, iv.length);
			System.arraycopy(cipherText, 0, result, iv.length, cipherText.length);
			return Base64.getEncoder().encodeToString(result);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("AES 加密失败", e);
		}
	}

	/**
	 * 解密
	 * @param cipherText encrypt 返回的 Base64 字符串
	 * @param key Base64 编码的 32 字节密钥
	 * @return 明文
	 */
	public static String decrypt(String cipherText, String key) {
		byte[] raw = decodeBase64(cipherText, "密文");
		if (raw.length <= IV_LENGTH) {
			throw new IllegalArgumentException("密文长度不合法");
		}
		try {
			byte[] iv = Arrays.copyOfRange(raw, 0, IV_LENGTH);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, toSecretKey(key), new GCMParameterSpec(TAG_LENGTH, iv));
			byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("AES 解密失败(密钥错误或密文被篡改)", e);
		}
	}

	private static SecretKeySpec toSecretKey(String key) {
		byte[] keyBytes = decodeBase64(key, "密钥");
		if (keyBytes.length != KEY_LENGTH) {
			throw new IllegalArgumentException("密钥长度必须为 32 字节(AES-256)");
		}
		return new SecretKeySpec(keyBytes, ALGORITHM);
	}

	private static byte[] decodeBase64(String value, String name) {
		try {
			return Base64.getDecoder().decode(value);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(name + "不是合法的 Base64 字符串", e);
		}
	}

}
