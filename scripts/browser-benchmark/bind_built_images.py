"""Bind built image IDs to exact reviewed sources AFTER strict JAR parity.

This updates only the sanitized evidence receipt, never either image or JAR.
"""
import argparse,json,os,re,subprocess
from pathlib import Path
from verify_source_pair import CONTROL,CANDIDATE

def bind(receipt,control,candidate):
    if receipt.get('controlSource')!=CONTROL or receipt.get('candidateSource')!=CANDIDATE or receipt.get('entryByteParity') is not True or receipt.get('normalizationPerformed') is not False or receipt.get('differingResourceEntries')!=[]:
        raise ValueError('Reviewed unmodified resource parity receipt required')
    result=dict(receipt)
    for arm,row,source in [('control',control,CONTROL),('candidate',candidate,CANDIDATE)]:
        identity=row.get('Id','')
        if not re.fullmatch('sha256:[a-f0-9]{64}',identity) or row.get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision')!=source or not re.fullmatch('[a-f0-9]{64}',receipt.get(arm+'JarSha256','')):
            raise ValueError('Actual exact locally-built image source binding failed')
        result[arm+'ImageId']=identity
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--receipt',type=Path,required=True);p.add_argument('--control',required=True);p.add_argument('--candidate',required=True);a=p.parse_args()
    if os.name!='posix' or os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_ENVIRONMENT')!='github-hosted':raise ValueError('Hosted CI only')
    rows=[]
    for image in (a.control,a.candidate):
        if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Immutable local image ID required')
        inspect=json.loads(subprocess.run(['docker','image','inspect',image],check=True,capture_output=True,text=True,timeout=10).stdout)
        if len(inspect)!=1 or inspect[0]['Id']!=image:raise ValueError('Image inspection cardinality/identity')
        rows.append(inspect[0])
    result=bind(json.loads(a.receipt.read_text()),*rows)
    temporary=a.receipt.with_suffix('.binding.pending');temporary.write_text(json.dumps(result,sort_keys=True,indent=2)+'\n');temporary.replace(a.receipt)
if __name__=='__main__':main()
