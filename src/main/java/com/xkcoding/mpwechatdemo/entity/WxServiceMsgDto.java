package com.xkcoding.mpwechatdemo.entity;

import lombok.Data;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "xml")
@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class WxServiceMsgDto {

     @XmlElement(name = "Event")
     private String event;

     @XmlElement(name = "Content")
     private String content;

     @XmlElement(name = "MsgType")
     private String msgType;

     @XmlElement(name = "ToUserName")
     private String toUserName;

     
     /**
      * fromUserName为关注人的openId
     **/

     @XmlElement(name = "FromUserName")
     private String fromUserName;

     @XmlElement(name="CreateTime")
     private String createTime;
}