package com.xkcoding.mpwechatdemo.entity;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 登录和注册请求参数json
 */
@Data
public class WechatLoginParam implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ticket;
    private String myId;
    private String jwtToken;

}
