"""Export the actual nine-tool discovery schema, without credentials or backend calls.

Run from mcp_server: python -m scripts.export_contract
"""
import asyncio
import json
from pathlib import Path
from fastmcp import Client
from homecare_mcp.server import create_server

async def main():
    async with Client(create_server()) as client:
        tools=await client.list_tools()
    contract={
        'format_version':'3.0.0','title':'HomeCare AI MCP executable contract',
        'status':'NINE_TOOLS_IMPLEMENTED','created_date':'2026-10-13',
        'timezone':'Asia/Seoul',
        'runtime':{'spring_ai':'2.0.1','java_mcp_sdk':'2.0.0','fastmcp':'3.4.7','transport':'Streamable HTTP / stdio'},
        'context_contract':{
            'authentication':'Request-scoped existing HomeCare user JWT (Authorization Bearer)',
            'ownership':'Spring owned conversation and product repositories',
            'context_version':'X-HomeCare-Context-Version header; result meta.context_version',
            'agent_hidden_input_properties':['conversation_id','request','region_override'],
            'candidate_binding':'SHA256(conversation ID, context revision, product ID/revision or 0, data version)',
            'model_code_provenance':'Spring accepts only a code typed in the latest customer message or the registered product model',
            'no_model_overrides':['user_id','email','token','prices','policy period','as-of date','purchase date','region'],
            'transport_security':'Strict Host/Origin validation; allowed hosts 127.0.0.1/localhost plus HOMECARE_MCP_ALLOWED_HOSTS'},
        'common_result_fields':{
            'source':'data_version, verified data files and fact IDs (evidence IDs)',
            'confidence':'HIGH | MEDIUM | LOW | UNDETERMINED',
            'caveats':'fixed data-interpretation notices; warnings stay situational',
            'independent_action_allowed':'always false; the MCP adapter rejects any other value'},
        'notes':[
            'tools are exported from actual FastMCP discovery; annotations are MCP standard fields.',
            'Spring Agent strips the user-only properties before exposing tools to the LLM.',
            'check_warranty supports conversation_id (Agent) OR request (manual compatibility), never both.',
            'The check_warranty union output has a FastMCP result wrapper; Java unwraps it.',
            'Common Envelope.data is tool-specific JSON; financial and warranty values are validated/calculated by Spring, not JSON Schema alone.',
            'Search results are candidates: EXCLUDED/other-model records are dropped, records without a model list are UNDETERMINED.',
            'estimate_repair_cost returns reference ranges (weekday visit fee basis) plus cost-table visit surcharges; never a quote or a waiver decision.',
            'Parts retention (manufacture-date basis) stays UNAVAILABLE until the B30 originals are verified.',
            'Tool implementation does not mean complete production data: approved-plans/reviews are empty, center seed has only 3 entries.'],
        'tools':[tool.model_dump(mode='json',by_alias=True,exclude_none=True) for tool in tools]}
    path=Path(__file__).resolve().parents[2]/'docs/mcp/mcp-tools.contract.json'
    path.write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Exported {len(tools)} actual tools to {path.name}')

if __name__=='__main__':asyncio.run(main())
