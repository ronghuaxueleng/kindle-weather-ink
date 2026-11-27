"""Offline project structure and XML sanity checks; not a replacement for Gradle."""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
required = [
    'settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
    'app/build.gradle', 'app/src/main/AndroidManifest.xml',
    'tools/bootstrap-gradle.sh', 'tools/bootstrap-gradle.ps1',
]
errors = []
for relative in required:
    if not (root / relative).is_file():
        errors.append('Missing: ' + relative)
for path in (root / 'app/src/main/res').rglob('*.xml'):
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        errors.append(str(path.relative_to(root)) + ': ' + str(exc))
try:
    ET.parse(root / 'app/src/main/AndroidManifest.xml')
except ET.ParseError as exc:
    errors.append('Manifest: ' + str(exc))
java_files = list((root / 'app/src/main/java').rglob('*.java'))
test_files = list((root / 'app/src/test/java').rglob('*.java'))
for path in java_files + test_files:
    text = path.read_text(encoding='utf-8')
    if not re.search(r'^package com\.github\.ronghuaxueleng\.remoteeink;', text, re.M):
        errors.append('Unexpected package: ' + str(path.relative_to(root)))
    relative = path.relative_to(root / 'app/src')
    expected = pathlib.Path(relative.parts[0]) / 'java/com/github/ronghuaxueleng/remoteeink'
    if relative.parent != expected:
        errors.append('Unexpected package directory: ' + str(path.relative_to(root)))
build = (root / 'app/build.gradle').read_text(encoding='utf-8')
for setting, expected in (('namespace', 'com.github.ronghuaxueleng.remoteeink'),
                          ('applicationId', 'com.github.ronghuaxueleng.kindleaidashboard')):
    if not re.search(r"\b" + setting + r"\s+'" + re.escape(expected) + r"'", build):
        errors.append('Unexpected ' + setting + ': expected ' + expected)
for pattern in (r'compileSdk\s+33\b', r'minSdk\s+17\b', r'targetSdk\s+28\b',
                r'versionCode\s+17\b', r"versionName\s+'1\.0\.7'"):
    if not re.search(pattern, build):
        errors.append('Unexpected version setting: ' + pattern)
if len(java_files) != 7:
    errors.append('Expected 7 Java source files, found ' + str(len(java_files)))
if errors:
    print('\n'.join(errors))
    sys.exit(1)
print('Project structure, XML, packages and Android version settings checked.')
print('Java source files:', len(java_files))
print('Gradle compilation, unit tests and device execution are still required.')
