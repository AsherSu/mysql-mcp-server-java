package com.example.exception;

public class MysqlMcpException extends RuntimeException {
    public MysqlMcpException(String response) {
        super(response);
    }
}
