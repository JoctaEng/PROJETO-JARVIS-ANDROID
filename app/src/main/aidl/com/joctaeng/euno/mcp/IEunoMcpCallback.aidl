package com.joctaeng.euno.mcp;

interface IEunoMcpCallback {
    oneway void onMessage(String jsonRpcMessage);
}
