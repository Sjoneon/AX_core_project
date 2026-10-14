import unittest
from unittest.mock import patch

import httpx
from fastmcp import Client
from fastmcp.exceptions import ToolError

from homecare_mcp.auth import request_token
from homecare_mcp.legacy.warranty_server import create_server


def result_fixture():
    def row(scope, months, status, expiry):
        return {"policyId": "TEST_" + scope, "scope": scope, "name": "시험용 보증",
                "variant": "TEST_MOTOR" if scope == "CORE_PART" else None,
                "periodMonths": months, "expiresOn": expiry, "periodStatus": status,
                "applicability": "USER_DECLARED_CONDITIONAL", "partFee": "CONDITIONAL_FREE" if status == "IN_WARRANTY" else "PAID",
                "laborFee": "CONFIRM_REQUIRED", "visitFee": "CONFIRM_REQUIRED", "conditions": ["TEST_ONLY"]}
    return {"category": "WASHER", "modelName": None, "asOfDate": "2026-10-13",
            "basisDate": "2023-10-13", "basisSource": "PURCHASE",
            "productWarranty": row("PRODUCT", 24, "EXPIRED", "2025-10-13"),
            "partWarranties": [row("CORE_PART", 48, "IN_WARRANTY", "2027-10-13")],
            "headline": "제품 보증 만료, 주요 부품 보증 유지", "notes": [],
            "policyVersion": "TEST_ONLY", "sourceUrl": "https://example.invalid/test",
            "policyVerifiedOn": "2026-10-13"}


class WarrantyMcpTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_mcp_discovery_and_call_keep_product_and_part_separate(self):
        requests = []

        def handler(request):
            requests.append(request)
            self.assertEqual(request.headers['authorization'], 'Bearer local-test-token')
            self.assertEqual(request.url.path, '/api/warranty/check')
            return httpx.Response(200, json=result_fixture())

        server = create_server("http://backend.test", lambda: "local-test-token", httpx.MockTransport(handler))
        async with Client(server) as client:
            tools = await client.list_tools()
            self.assertEqual([tool.name for tool in tools], ['check_warranty'])
            self.assertNotIn('token', str(tools[0].inputSchema))
            result = await client.call_tool('check_warranty', {'request': {'category': 'WASHER', 'purchaseDate': '2023-10-13'}})
            output = result.structured_content
            self.assertEqual(output['productWarranty']['periodMonths'], 24)
            self.assertEqual(output['partWarranties'][0]['periodMonths'], 48)
            self.assertEqual(output['partWarranties'][0]['visitFee'], 'CONFIRM_REQUIRED')
        self.assertEqual(len(requests), 1)

    async def test_no_authentication_never_contacts_backend(self):
        def missing_token():
            raise ToolError('AUTH_REQUIRED')

        def handler(_):
            self.fail('Unauthenticated request must not reach the backend')

        async with Client(create_server('http://backend.test', missing_token, httpx.MockTransport(handler))) as client:
            with self.assertRaisesRegex(Exception, 'AUTH_REQUIRED'):
                await client.call_tool('check_warranty', {'request': {'category': 'WASHER'}})

    async def test_model_cannot_override_policy_period(self):
        def handler(_):
            self.fail('Policy override must be rejected before the backend call')

        server = create_server('http://backend.test', lambda: 'test', httpx.MockTransport(handler))
        async with Client(server) as client:
            with self.assertRaises(Exception):
                await client.call_tool('check_warranty', {'request': {'category': 'WASHER', 'periodMonths': 999}})

    async def test_untrusted_upstream_body_is_not_returned(self):
        transport = httpx.MockTransport(lambda _: httpx.Response(500, text='upstream-sensitive-detail'))
        async with Client(create_server('http://backend.test', lambda: 'test', transport)) as client:
            with self.assertRaises(Exception) as caught:
                await client.call_tool('check_warranty', {'request': {'category': 'WASHER'}})
            self.assertIn('WARRANTY_UNAVAILABLE', str(caught.exception))
            self.assertNotIn('upstream-sensitive-detail', str(caught.exception))

    async def test_backend_validation_is_a_safe_tool_error(self):
        transport = httpx.MockTransport(lambda _: httpx.Response(400, json={'message': 'private raw input'}))
        async with Client(create_server('http://backend.test', lambda: 'test', transport)) as client:
            with self.assertRaisesRegex(Exception, 'INVALID_ARGUMENT'):
                await client.call_tool('check_warranty', {'request': {'category': 'DISHWASHER', 'purchaseDate': '2099-01-01'}})

    async def test_timeout_never_generates_a_warranty_answer(self):
        def handler(_):
            raise httpx.ReadTimeout('timeout')

        async with Client(create_server('http://backend.test', lambda: 'test', httpx.MockTransport(handler))) as client:
            with self.assertRaisesRegex(Exception, 'WARRANTY_UNAVAILABLE'):
                await client.call_tool('check_warranty', {'request': {'category': 'DRYER'}})

    def test_backend_origin_must_be_http(self):
        with self.assertRaises(ValueError):
            create_server('file:///private-path')

    def test_http_never_reuses_global_user_token(self):
        with patch.dict('os.environ', {'HOMECARE_MCP_TRANSPORT': 'http', 'HOMECARE_USER_TOKEN': 'unrelated-test-user'}):
            with patch('homecare_mcp.auth.get_http_headers', return_value={}):
                with self.assertRaisesRegex(ToolError, 'AUTH_REQUIRED'):
                    request_token()

    def test_http_authorization_is_request_scoped(self):
        with patch.dict('os.environ', {'HOMECARE_MCP_TRANSPORT': 'http'}):
            with patch('homecare_mcp.auth.get_http_headers', side_effect=[{'authorization': 'Bearer user-a-test'}, {'authorization': 'Bearer user-b-test'}]):
                self.assertEqual(request_token(), 'user-a-test')
                self.assertEqual(request_token(), 'user-b-test')


if __name__ == '__main__':
    unittest.main()
