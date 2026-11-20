"""Idempotent bucket initialization via S3 SigV4, without a separate mc image."""
import datetime
import hashlib
import hmac
import os
import time
import urllib.error
import urllib.request


def create_bucket():
    endpoint = 'http://objects:9000'
    bucket = 'stories'
    access, secret = os.environ['ACCESS'], os.environ['SECRET']
    now = datetime.datetime.now(datetime.timezone.utc)
    date, stamp = now.strftime('%Y%m%d'), now.strftime('%Y%m%dT%H%M%SZ')
    payload = hashlib.sha256(b'').hexdigest()
    headers = f'host:objects:9000\nx-amz-content-sha256:{payload}\nx-amz-date:{stamp}\n'
    signed = 'host;x-amz-content-sha256;x-amz-date'
    canonical = f'PUT\n/{bucket}\n\n{headers}\n{signed}\n{payload}'
    scope = f'{date}/us-east-1/s3/aws4_request'
    value = f'AWS4-HMAC-SHA256\n{stamp}\n{scope}\n{hashlib.sha256(canonical.encode()).hexdigest()}'
    key = ('AWS4' + secret).encode()
    for part in [date, 'us-east-1', 's3', 'aws4_request']:
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, value.encode(), hashlib.sha256).hexdigest()
    auth = f'AWS4-HMAC-SHA256 Credential={access}/{scope}, SignedHeaders={signed}, Signature={signature}'
    request = urllib.request.Request(endpoint + '/' + bucket, data=b'', method='PUT', headers={'Authorization': auth, 'x-amz-content-sha256': payload, 'x-amz-date': stamp})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status == 200
    except urllib.error.HTTPError as error:
        # Only a bucket already owned by these credentials is considered success.
        if error.code == 409 and b'<Code>BucketAlreadyOwnedByYou</Code>' in error.read():
            return True
        raise


if __name__ == '__main__':
    for attempt in range(60):
        try:
            if create_bucket():
                print('Object bucket ready')
                break
        except (urllib.error.URLError, TimeoutError):
            if attempt == 59:
                raise SystemExit('Object bucket initialization failed; check endpoint and credentials')
            time.sleep(2)
