#!/usr/bin/env python3
"""Annotations GitHub à partir des rapports surefire : un message par test en échec (message + pile du projet)."""
import glob, sys, xml.etree.ElementTree as ET

def enc(s):
    return s.replace('%', '%25').replace('\r', '').replace('\n', '%0A')

count = 0
for f in sorted(glob.glob(sys.argv[1] if len(sys.argv) > 1 else '**/target/surefire-reports/TEST-*.xml', recursive=True)):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for tc in root.iter('testcase'):
        for kind in ('failure', 'error'):
            el = tc.find(kind)
            if el is None:
                continue
            msg = (el.get('message') or '')[:1200]
            stack = [l for l in (el.text or '').splitlines()
                     if 'cm.camtel' in l or 'Caused by' in l or l.startswith(('org.opentest4j', 'java.lang', 'org.springframework.dao'))]
            body = f"{tc.get('classname')}.{tc.get('name')} ({kind})\n{msg}\n" + '\n'.join(stack[:12])
            body = body.encode('utf-8')[:3000].decode('utf-8', 'ignore')
            print(f"::error title=Test en échec {count + 1}::{enc(body)}")
            count += 1
            if count >= 8:
                sys.exit(0)
