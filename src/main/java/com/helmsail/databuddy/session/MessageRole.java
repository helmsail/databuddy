package com.helmsail.databuddy.session;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 会话消息角色(session_message.role 存 name())
 */
public enum MessageRole {

	USER, ASSISTANT;

	/** 请求体解析:大小写不敏感("user"/"ASSISTANT" 均可);非法值返回 null,由 Service 统一报 400 */
	@JsonCreator
	public static MessageRole from(String value) {
		if (value == null) {
			return null;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
