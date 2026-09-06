#!/usr/bin/env python3
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
from report_event import deliver, read_token

EVENT = {"id": "fixture:run:1", "outcome": "SUCCESS", "verification": "VERIFIED", "changes": []}


class Response:
    def __init__(self, code, value): self.status, self.value = code, value
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, limit): return json.dumps(self.value).encode()


class ReportingTest(unittest.TestCase):
    def test_lost_reply_retries_identical_payload_and_accepts_duplicate(self):
        seen = []
        def server(request, timeout):
            seen.append(request.data)
            self.assertEqual('fixture-secret', request.get_header('X-dora-ingest-token'))
            if len(seen) == 1: raise urllib.error.URLError('lost reply after commit')
            return Response(200, {'id': EVENT['id'], 'disposition': 'DUPLICATE'})
        with patch('report_event._open', side_effect=server), patch('time.sleep'):
            receipt = deliver(EVENT, 'fixture-secret', 'http://127.0.0.1/fixture')
        self.assertEqual(2, len(seen))
        self.assertEqual(seen[0], seen[1])
        self.assertEqual('DUPLICATE', receipt['disposition'])
        self.assertNotIn('fixture-secret', json.dumps(receipt))

    def test_accepted_status_without_acknowledgment_is_failure(self):
        for code, answer in [(200, {}), (201, {'id':'wrong', 'disposition':'STORED'}),
                             (200, {'id':EVENT['id'], 'disposition':'STORED'})]:
            with self.subTest(code=code, answer=answer), patch('report_event._open', return_value=Response(code,answer)):
                with self.assertRaises(ValueError): deliver(EVENT, 'fixture-secret', 'http://127.0.0.1/fixture')

    def test_permanent_rejection_does_not_retry(self):
        error = urllib.error.HTTPError('http://127.0.0.1/fixture', 409, 'conflict', {}, None)
        with patch('report_event._open', side_effect=error) as call:
            with self.assertRaisesRegex(ValueError, '409'): deliver(EVENT, 'fixture-secret', 'http://127.0.0.1/fixture')
        self.assertEqual(1,call.call_count)

    def test_outage_exhausts_bounded_retries(self):
        with patch('report_event._open', side_effect=urllib.error.URLError('unreachable')) as call, patch('time.sleep'):
            with self.assertRaisesRegex(ValueError, 'exhausted'): deliver(EVENT, 'fixture-secret', 'http://127.0.0.1/fixture')
        self.assertEqual(3,call.call_count)

    def test_new_event_acknowledged(self):
        with patch('report_event._open', return_value=Response(201, {'id': EVENT['id'], 'disposition':'STORED'})):
            self.assertEqual(201,deliver(EVENT,'fixture-secret','http://127.0.0.1/fixture')['httpStatus'])

    def test_token_is_read_without_executing_environment_file(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'app.env'
            path.write_text('UNRELATED=ignore\nDORA_INGEST_TOKEN="fixture-secret"\n')
            self.assertEqual('fixture-secret',read_token(path))
            path.write_text('DORA_INGEST_TOKEN=one\nDORA_INGEST_TOKEN=two\n')
            with self.assertRaises(ValueError): read_token(path)
            path.write_text('DORA_INGEST_TOKEN=\n')
            with self.assertRaises(ValueError): read_token(path)



class ActualHttpReportingTest(unittest.TestCase):
    def server(self, actions):
        import http.server
        import threading
        import contextlib
        seen = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args): pass
            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                seen.append((body, self.headers.get('X-Dora-Ingest-Token')))
                action = actions[min(len(seen)-1, len(actions)-1)]
                if action == 'drop':
                    self.close_connection = True
                    self.connection.close()
                    return
                code, value = action
                data = json.dumps(value).encode()
                self.send_response(code)
                if code == 302:
                    self.send_header('Location', '/redirected')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        @contextlib.contextmanager
        def running():
            httpd = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                yield f'http://127.0.0.1:{httpd.server_port}/api/v1/deployments', seen
            finally:
                httpd.shutdown(); httpd.server_close(); thread.join()
        return running()

    def test_real_lost_ack_then_duplicate_preserves_exact_bytes_and_token(self):
        with self.server(['drop', (200, {'id':EVENT['id'],'disposition':'DUPLICATE'})]) as (url,seen), patch('time.sleep'):
            receipt = deliver(EVENT, 'fixture-secret', url)
        self.assertEqual('DUPLICATE', receipt['disposition'])
        self.assertEqual(2, len(seen))
        self.assertEqual(seen[0], seen[1])
        self.assertEqual('fixture-secret', seen[0][1])

    def test_real_server_errors_retry_but_redirect_never_forwards_token(self):
        with self.server([(503, {}),(201, {'id':EVENT['id'],'disposition':'STORED'})]) as (url,seen), patch('time.sleep'):
            self.assertEqual(201,deliver(EVENT,'fixture-secret',url)['httpStatus'])
        self.assertEqual(2,len(seen))
        with self.server([(302,{})]) as (url,seen):
            with self.assertRaisesRegex(ValueError,'302'):
                deliver(EVENT,'fixture-secret',url)
        self.assertEqual(1,len(seen))

    def test_real_rejection_or_wrong_identity_produces_no_receipt_and_no_secret_output(self):
        import subprocess
        for code,answer in [(400,{'message':'unsupported verification'}),
                            (200,{'id':'different','disposition':'DUPLICATE'})]:
            with self.subTest(code=code), self.server([(code,answer)]) as (url,seen), tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                event=root/'event.json'; event.write_text(json.dumps(EVENT))
                token=root/'app.env'; token.write_text('DORA_INGEST_TOKEN=fixture-secret\n')
                receipt=root/'receipt.json'; receipt.write_text('stale acknowledgment')
                result=subprocess.run(['python3',str(Path(__file__).with_name('report_event.py')),
                    str(event),str(receipt),'--token-file',str(token),'--endpoint',url],capture_output=True,text=True)
                self.assertEqual(1,result.returncode)
                self.assertFalse(receipt.exists())
                self.assertEqual(EVENT,json.loads(event.read_text()))
                self.assertNotIn('fixture-secret',result.stdout+result.stderr)
                self.assertEqual(1,len(seen))

if __name__ == '__main__': unittest.main()
