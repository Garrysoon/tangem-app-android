#!/usr/bin/env python3
import os

fp = r'C:\Users\Garrysan\tangem-app-android\app\src\main\java\com\tangem\tap\data\SecurityOSCommandMapper.kt'
with open(fp, 'r', encoding='utf-8') as f:
    c = f.read()

# 1. Add cachedCardId field
old1 = '    private var cachedAuthentikey: ByteArray? = None'
new1 = '    private var cachedAuthentikey: ByteArray? = None\n    private var cachedCardId: ByteArray? = None'
# fix None -> null
old1 = old1.replace('None', 'null')
new1 = new1.replace('None', 'null')
if old1 in c and old1 not in ['None']:
    c = c.replace(old1, new1)
    print('1. cachedCardId field added')
else:
    print('1. cachedCardId already exists or not found')

with open(fp, 'w', encoding='utf-8') as f:
    f.write(c)
print('DONE step 1')
