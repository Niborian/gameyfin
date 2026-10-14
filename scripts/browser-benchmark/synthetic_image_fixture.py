"""Original deterministic raster fixture and strict normal-image HTTP evidence.

No network, file access, DB seeding, cache dropping or server mutations here.
Harness owns writing returned bytes into a separate readonly fixture-images mount.
"""
import hashlib, struct, zlib

WIDTH, HEIGHT = 320, 480

def _chunk(kind, data):
    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)

def cover_bytes():
    rows=[]
    for y in range(HEIGHT):
        rows.append(b'\0'+bytes(component for x in range(WIDTH) for component in
            ((x*7+y*3)%256,(x*5+y*11)%256,(x^y)%256)))
    return b'\x89PNG\r\n\x1a\n'+_chunk(b'IHDR',struct.pack('>IIBBBBB',WIDTH,HEIGHT,8,2,0,0,0))+_chunk(b'IDAT',zlib.compress(b''.join(rows),9))+_chunk(b'IEND',b'')

def fixture_manifest():
    data=cover_bytes()
    return {'width':WIDTH,'height':HEIGHT,'mimeType':'image/png','byteLength':len(data),
        'sha256':hashlib.sha256(data).hexdigest(),'scope':'original synthetic cover; not provider-image acquisition benchmark'}

def update_game_body():
    fields=('title','platforms','release','headerUrl','comment','summary','developers','publishers',
        'genres','themes','features','perspectives','keywords','metadata')
    game={key:None for key in fields}
    game.update(id=33000,coverUrl='file:///fixture-images/cover.png')
    return {'game':game}

def cover_route(games):
    targets=[game for game in games if game.get('id')==33000]
    if len(targets)!=1:raise ValueError('Exact synthetic game33000 required')
    cover=targets[0].get('cover') or {}
    identifier=cover.get('id')
    if type(identifier)!=int or identifier<=0 or cover.get('type')!='COVER' or not cover.get('blurhash'):
        raise ValueError('Normal ingestion must yield persisted cover identity and blurhash')
    return '/images/cover/'+str(identifier)

def validate_response(status,headers,body,expected_etag=None,conditional=False):
    if len(body)>1024*1024:raise ValueError('Image response bound exceeded')
    etag=headers.get('ETag') or headers.get('etag')
    if not etag or len(etag)>80 or not(etag.startswith('"') and etag.endswith('"')):raise ValueError('Content ETag missing')
    if expected_etag is not None and etag!=expected_etag:raise ValueError('Content identity changed')
    cache=headers.get('Cache-Control') or headers.get('cache-control') or ''
    if 'max-age=604800' not in cache:raise ValueError('Normal image cache policy changed')
    if conditional:
        if status!=304 or body:raise ValueError('Conditional image cache semantics changed')
        return {'status':304,'etag':etag,'bodyBytes':0,'conditionalCacheVerified':True}
    expected=fixture_manifest()
    if status!=200 or headers.get('Content-Type',headers.get('content-type','')).split(';')[0]!='image/png':raise ValueError('Image response type/status changed')
    if len(body)!=expected['byteLength'] or hashlib.sha256(body).hexdigest()!=expected['sha256']:raise ValueError('Image bytes changed')
    # Decode this fixture's strict RGB8 PNG using bounded chunks/inflation.
    if body[:8]!=b'\x89PNG\r\n\x1a\n':raise ValueError('PNG signature')
    offset=8;payload=b'';dimensions=None
    while offset<len(body):
        length=struct.unpack('>I',body[offset:offset+4])[0];kind=body[offset+4:offset+8]
        data=body[offset+8:offset+8+length];crc=body[offset+8+length:offset+12+length]
        if len(crc)!=4 or struct.unpack('>I',crc)[0]!=(zlib.crc32(kind+data)&0xffffffff):raise ValueError('PNG chunk integrity')
        if kind==b'IHDR':dimensions=struct.unpack('>IIBBBBB',data)
        elif kind==b'IDAT':payload+=data
        offset+=12+length
    if dimensions!=(WIDTH,HEIGHT,8,2,0,0,0):raise ValueError('PNG dimensions/encoding changed')
    inflater=zlib.decompressobj();pixels=inflater.decompress(payload,HEIGHT*(1+WIDTH*3)+1)
    if len(pixels)!=HEIGHT*(1+WIDTH*3) or not inflater.eof or inflater.unused_data:raise ValueError('PNG pixel decode failed')
    if any(pixels[y*(1+WIDTH*3)]!=0 for y in range(HEIGHT)):raise ValueError('PNG row encoding changed')
    return {'status':200,'etag':etag,'bodyBytes':len(body),'sha256':expected['sha256'],'decodedWidth':WIDTH,'decodedHeight':HEIGHT}
