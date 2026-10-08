package com.helmsail.databuddy.middle.graph.intent;

/**
 * 意图域状态键(域内自洽:本包节点写、本包分流器读;跨域共用的键在 GraphKeys)
 */
public final class IntentKeys {

	/** 意图分类结果(取值见 IntentType;IntentRecognitionDispatcher 据此分流) */
	public static final String CLASSIFICATION = "classification";

	private IntentKeys() {
	}

}
