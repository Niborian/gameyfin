"""Pure adapter: no DB, server, files, mounts, HTTP or auth operations."""
PLUGIN_ID='org.gameyfin.experiments.fixture.metadata'

def expected_ids():
    return set(range(31000,31104)) | set(range(32000,32027)) | {33000}

def seed_appendix(manifest):
    games=manifest['games']
    if len(games)!=132 or {game['gameId'] for game in games}!=expected_ids(): raise ValueError('exact fixture IDs required')
    lines=[]
    for library in range(27001,27005):
        lines.append("INSERT INTO LIBRARY_PLATFORMS(LIBRARY_ID,PLATFORMS) VALUES(%d,'PC_MICROSOFT_WINDOWS');"%library)
    for game in games:
        identifier=game['gameId']
        lines.append("INSERT INTO GAME_ORIGINAL_IDS(GAME_ID,ORIGINAL_IDS,ORIGINAL_IDS_KEY) VALUES(%d,'fixture-%d','%s');"%(identifier,identifier,PLUGIN_ID))
    return '\n'.join(lines)+'\n'

def validate_enrichment(games,manifest,expected_count=132):
    expected={game['gameId']:game for game in manifest['games']}
    if expected_count not in (1,132) or (expected_count==1 and set(expected)!={33000}): raise ValueError('diagnostic target must be exact game33000')
    if len(games)!=expected_count or {game['id'] for game in games}!=set(expected): raise ValueError('game identity changed')
    for game in games:
        identifier=game['id']; seeded=expected[identifier]
        if game['title']!='Enriched fixture game '+str(identifier): raise ValueError('positive title absent')
        if game['summary']!='Deterministic enrichment for synthetic game '+str(identifier): raise ValueError('summary absent')
        if game['platforms']!=['PC (Microsoft Windows)']: raise ValueError('library platform filter changed')
        if game['developers']!=['Fixture Studio'] or game['publishers']!=['Fixture Publisher']: raise ValueError('company enrichment absent')
        if game['genres']!=['Action'] or game['keywords']!=['synthetic-positive-provider']: raise ValueError('classification enrichment absent')
        if game['metadata']['originalIds']!={PLUGIN_ID:'fixture-'+str(identifier)}: raise ValueError('provider identity changed')
        pinned=[variant for variant in game['variants'] if variant['id']==seeded['variantId']]
        if len(pinned)!=1 or not pinned[0]['default'] or not pinned[0]['defaultLocked']: raise ValueError('pinned variant changed')
        contents={content['id']:content for content in pinned[0]['contents']}
        if set(contents)!={seeded['requiredContentId'],seeded['optionalContentId']}: raise ValueError('content identity changed')
        required=contents[seeded['requiredContentId']]; optional=contents[seeded['optionalContentId']]
        if not required['required'] or required['pathCount']!=2 or optional['required'] or optional['defaultSelected']: raise ValueError('selection semantics changed')
    return {'positiveEnrichmentVerified':True,'games':expected_count,'providerId':PLUGIN_ID}

def validate_source_manifest(manifest,actual_hashes):
    expected=manifest['sha256']
    if manifest['sourceFileCount']!=924 or len(expected)!=924 or actual_hashes!=expected:
        raise ValueError('synthetic source count/bytes changed')
    return {'sourceFileCount':924,'sourceFilesUnchanged':True}
