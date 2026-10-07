// Contrato público "MCP no celular" (ADR 0011). Qualquer app que queira ser controlado pelo Euno
// exporta um serviço com a ação com.joctaeng.euno.action.MCP_SERVER que implementa esta interface.
// As mensagens são JSON-RPC 2.0 do Model Context Protocol (initialize, tools/list, tools/call).
package com.joctaeng.euno.mcp;

import com.joctaeng.euno.mcp.IEunoMcpCallback;

interface IEunoMcpServer {
    /** Envia uma mensagem JSON-RPC; respostas (e notificações) chegam pelo callback. */
    oneway void send(String jsonRpcMessage, IEunoMcpCallback callback);
}
