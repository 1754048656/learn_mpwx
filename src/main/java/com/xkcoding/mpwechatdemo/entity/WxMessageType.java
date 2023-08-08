package com.xkcoding.mpwechatdemo.entity;

import lombok.Getter;

@Getter
public enum WxMessageType {

    /**
     * 事件类型，比如订阅与取消订阅
     */
    EVENT("event"),
    /**
     * 向公众号发送的文字消息
     */
    TEXT("text");

    private String code;

    WxMessageType(String code) {
        this.code = code;
    }
}