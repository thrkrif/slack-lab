package com.slack.lab.core.model;

/** 이 봇의 식별자. 스레드에서 "내가 한 말"과 다른 봇의 말을 가르는 데 쓴다. */
public record BotIdentity(String userId, String botId) {}
