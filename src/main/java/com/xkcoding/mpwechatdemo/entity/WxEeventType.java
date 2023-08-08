package com.xkcoding.mpwechatdemo.entity;

import lombok.Getter;

@Getter
public enum WxEeventType {
    /**
     * 关注公众号
     */
    SUBSCRIBE("subscribe"),
    /**
     * 取消关注公众号
     */
    UNSUBSCRIBE("unsubscribe");

    private String code;

    WxEeventType(String code) {
        this.code = code;
    }
}