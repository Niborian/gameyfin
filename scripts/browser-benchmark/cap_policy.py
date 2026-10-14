"""Strict Docker capability spelling equivalence, never permission expansion.

Moby client PR48551 (Docker28+) canonicalizes SYS_ADMIN to CAP_SYS_ADMIN;
ALL is a special value. Compare only the explicitly expected list with equal
cardinality and no duplicated semantic capabilities. Unknown caps fail closed.
Primary: https://github.com/moby/moby/pull/48551
"""
import re

def canonical(value):
    if not isinstance(value,str) or not re.fullmatch('[A-Z][A-Z0-9_]{0,63}',value):
        raise ValueError('Capability enum boundary')
    return value if value=='ALL' or value.startswith('CAP_') else 'CAP_'+value

def caps_match(actual,expected):
    if actual is None:actual=[]
    if not isinstance(actual,list) or not isinstance(expected,(list,tuple)) or len(actual)!=len(expected) or len(actual)>64:
        return False
    try:observed=[canonical(v) for v in actual];required=[canonical(v) for v in expected]
    except ValueError:return False
    return len(set(observed))==len(observed) and len(set(required))==len(required) and set(observed)==set(required)
