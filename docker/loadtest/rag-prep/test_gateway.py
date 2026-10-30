import concurrent.futures
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from model_gateway import Ledger,Gateway,ThreadingHTTPServer,NoRedirect
from urllib.error import HTTPError
from urllib.request import build_opener


class BudgetTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.path=Path(self.temp.name)/'ledger.json'
    def tearDown(self): self.temp.cleanup()

    def test_concurrent_reservations_share_one_call_cap(self):
        ledger=Ledger(self.path,limit=8,budget=5)
        def attempt(_):
            try: return ledger.reserve(.5)
            except ValueError: return None
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool: accepted=list(filter(None,pool.map(attempt,range(24))))
        self.assertEqual(len(accepted),8)
        self.assertEqual(ledger.state()['reservedUsd'],4)
        self.assertEqual(ledger.state()['attempts'],8)

    def test_inflight_cost_and_missing_usage_remain_reserved_after_restart(self):
        ledger=Ledger(self.path,budget=1); ledger.reserve(.7)
        with self.assertRaises(ValueError): ledger.reserve(.4)
        restored=Ledger(self.path,budget=1)
        self.assertTrue(restored.state()['stopped']); self.assertEqual(restored.state()['reservedUsd'],.7)
        with self.assertRaises(ValueError): restored.reserve(.1)

    def test_settlement_preserves_spend_and_call_count(self):
        ledger=Ledger(self.path,limit=2,budget=1); key=ledger.reserve(.5); ledger.settle(key,.2)
        restored=Ledger(self.path,limit=2,budget=1); restored.reserve(.5)
        self.assertAlmostEqual(restored.state()['estimatedUsd'],.2)
        with self.assertRaises(ValueError): restored.reserve(0)

    def test_usage_outside_reservation_stops_without_releasing_hold(self):
        ledger=Ledger(self.path); key=ledger.reserve(.2)
        with self.assertRaises(ValueError): ledger.settle(key,.3)
        self.assertTrue(ledger.state()['stopped']); self.assertEqual(ledger.state()['reservedUsd'],.2)


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.handler=type('TestGateway',(Gateway,),dict(ledger=Ledger(Path(self.temp.name)/'ledger.json',limit=5),mode='synthetic',records=None))
        self.server=ThreadingHTTPServer(('127.0.0.1',0),self.handler)
        self.thread=threading.Thread(target=self.server.serve_forever); self.thread.start()
    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.temp.cleanup()

    def post(self,path,body,chunked=False):
        client=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        raw=json.dumps(body).encode()
        client.request('POST',path,body=iter([raw[:20],raw[20:]]) if chunked else raw,
                       headers={'Authorization':'Bearer rag-internal-dummy','Content-Type':'application/json'},encode_chunked=chunked)
        reply=client.getresponse(); status=reply.status; content=reply.read(); client.close(); return status,content

    def question(self):
        return dict(model='gpt-5.4-mini',messages=[dict(role='system',content='법률 질문의 유형'),dict(role='user',content='검증 질문')],n=None)

    def test_chunked_body_and_missing_role_limit_are_bounded(self):
        status,raw=self.post('/v1/chat/completions',self.question(),True)
        self.assertEqual(status,200); self.assertEqual(json.loads(json.loads(raw)['choices'][0]['message']['content'])['type'],'LAW_EXPLANATION')
        self.assertEqual(self.handler.ledger.state()['attempts'],1)
        self.assertEqual(self.handler.ledger.state()['reservedUsd'],0)

    def test_unsupported_endpoint_model_and_tools_never_reserve(self):
        body=self.question(); self.assertEqual(self.post('/v1/embeddings',body)[0],403)
        body['model']='unpriced'; self.assertEqual(self.post('/v1/chat/completions',body)[0],502)
        body['model']='gpt-5.4-mini'; body['tools']=[{}]; self.assertEqual(self.post('/v1/chat/completions',body)[0],502)
        self.assertEqual(self.handler.ledger.state()['attempts'],0)

    def test_stream_includes_terminal_and_usage_without_paying(self):
        body=dict(model='gpt-5.4-mini',stream=True,max_completion_tokens=4096,messages=[dict(role='user',content='[[00000000-0000-0000-0000-000000000001]]')])
        status,raw=self.post('/v1/chat/completions',body)
        self.assertEqual(status,200); self.assertIn(b'data: [DONE]',raw); self.assertIn(b'prompt_tokens',raw)
        self.assertEqual(self.handler.ledger.state()['estimatedUsd'],0)

    def test_paid_transport_refuses_redirects(self):
        from http.server import BaseHTTPRequestHandler
        class Redirect(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                self.send_response(302); self.send_header('Location','http://127.0.0.1:1/never'); self.end_headers()
        server=ThreadingHTTPServer(('127.0.0.1',0),Redirect)
        worker=threading.Thread(target=server.serve_forever); worker.start()
        try:
            with self.assertRaises(HTTPError) as failure: build_opener(NoRedirect()).open(f'http://127.0.0.1:{server.server_port}/redirect',timeout=2)
            self.assertEqual(failure.exception.code,302)
        finally: server.shutdown(); server.server_close(); worker.join()


if __name__=='__main__': unittest.main()
