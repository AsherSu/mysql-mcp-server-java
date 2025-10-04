package com.example;

public class MysqlMcpException extends RuntimeException {
    public MysqlMcpException(String response) {
        super(response);
    }
}
