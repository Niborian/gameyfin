"""Failure-only bounded transport and preservation checks; no network."""
import http.client,io,socket,unittest
from types import SimpleNamespace
from unittest.mock import patch
import run_one_app_probe as runner

class FailureObservationTests(unittest.TestCase):
    def read(self,*,truncated=False,chunked=False,late_header=False,late_body=False):
        now=[0.0];owned=socket.socket();body=b'{"status":"UP"}'
        class Stream(io.BytesIO):
            raw=SimpleNamespace(_sock=owned)
            def read1(self,n):
                value=super().read1(n)
                if late_body:now[0]=2.1
                return value
            def close(self):owned.close();super().close()
        headers=b'Transfer-Encoding: chunked'if chunked else b'Content-Length: '+str(len(body)+(5 if truncated else 0)).encode()
        payload=(hex(len(body))[2:].encode()+b'\r\n'+body+b'\r\n0\r\n\r\n')if chunked else body
        stream=Stream(b'HTTP/1.1 200 OK\r\n'+headers+b'\r\n\r\n'+payload)
        response=http.client.HTTPResponse(SimpleNamespace(makefile=lambda *a:stream));response.begin()
        class Connection:
            sock=owned
            def request(self,*a):now[0]=0.5
            def getresponse(self):
                if late_header:now[0]=2.1
                return response
            def close(self):response.close();owned.close()
        with patch.object(runner.http.client,'HTTPConnection',return_value=Connection()):
            return runner.failure_http('172.22.0.2','/actuator/health',clock=lambda:now[0])
    def test_content_length_and_chunked_completion(self):
        for chunked in(False,True):self.assertEqual(self.read(chunked=chunked)[2],b'{"status":"UP"}')
    def test_late_header_body_and_truncated_content_fail_closed(self):
        for options in({'late_header':True},{'late_body':True},{'truncated':True}):
            with self.subTest(options=options),self.assertRaises(TimeoutError):self.read(**options)
    def test_low_remaining_budget_never_touches_app(self):
        report={};runner.observe_failure(object(),report,lambda:None,lambda:39.9)
        self.assertEqual(report['failureObservation']['status'],'budget-skipped')
        self.assertAlmostEqual(report['failureObservation']['controllerRemainingSeconds'],39.9)
    def test_detached_response_close_failure_still_closes_connection(self):
        owned=socket.socket();closed=[]
        response=SimpleNamespace(fp=SimpleNamespace(raw=SimpleNamespace(_sock=owned)),status=200,length=0,
            isclosed=lambda:True,close=lambda:(_ for _ in()).throw(OSError('private close failure')))
        connection=SimpleNamespace(sock=owned,request=lambda *a:None,getresponse=lambda:response,close=lambda:closed.append(True))
        try:
            with patch.object(runner.http.client,'HTTPConnection',return_value=connection):
                with self.assertRaises(OSError):runner.failure_http('172.22.0.2','/actuator/health')
            self.assertEqual(closed,[True])
        finally:owned.close()
    def test_request_failure_closes_connection_without_response(self):
        closed=[]
        connection=SimpleNamespace(request=lambda *a:(_ for _ in()).throw(TimeoutError()),close=lambda:closed.append(True))
        with patch.object(runner.http.client,'HTTPConnection',return_value=connection):
            with self.assertRaises(TimeoutError):runner.failure_http('172.22.0.2','/actuator/health')
        self.assertEqual(closed,[True])

if __name__=='__main__':unittest.main()
