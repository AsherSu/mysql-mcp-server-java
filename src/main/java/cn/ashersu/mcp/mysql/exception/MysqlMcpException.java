package cn.ashersu.mcp.mysql.exception;

public class MysqlMcpException extends RuntimeException {
    public MysqlMcpException(String response) {
        super(response);
    }
}
