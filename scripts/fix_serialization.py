#!/usr/bin/env python3
import re

for path in [
    "engine/singbox/src/main/kotlin/com/novavpn/engine/singbox/SingboxConfigParser.kt",
    "engine/xray/src/main/kotlin/com/novavpn/engine/xray/XrayConfigParser.kt"
]:
    with open(path) as f:
        lines = f.readlines()
    
    new_lines = []
    i = 0
    var_counter = 0
    while i < len(lines):
        line = lines[i].rstrip('\n')
        s = line.strip()
        
        m = re.match(r'(.*)put\("([^"]+)",\s*(buildJson\w+)\s*\{\s*$', line)
        if m:
            indent = m.group(1)
            key = m.group(2)
            builder = m.group(3)
            
            brace_depth = 0
            block_lines = [line]
            found_close = False
            for j in range(i + 1, len(lines)):
                block_lines.append(lines[j].rstrip('\n'))
                for ch in lines[j]:
                    if ch == '{': brace_depth += 1
                    elif ch == '}': brace_depth -= 1
                if brace_depth <= 0:
                    found_close = True
                    break
            
            if found_close:
                var_counter += 1
                var_name = f"_{key.replace('-','_').replace('.','_')}_{var_counter}"
                
                # Replace first line: remove put() wrapper
                new_lines.append(f"{indent}val {var_name} = {builder} {{")
                
                # Middle lines stay the same
                for k in range(1, len(block_lines) - 1):
                    new_lines.append(block_lines[k])
                
                # Last line: remove the final ) to close just the block
                last = block_lines[-1].strip()
                if last.endswith('})'):
                    indent_l = block_lines[-1][:len(block_lines[-1]) - len(block_lines[-1].lstrip())]
                    new_lines.append(f"{indent_l}}}")
                    new_lines.append(f"{indent}put(\"{key}\", {var_name})")
                elif last.endswith('})}}'):
                    indent_l = block_lines[-1][:len(block_lines[-1]) - len(block_lines[-1].lstrip())]
                    new_lines.append(f"{indent_l}}}")
                    new_lines.append(f"{indent}}}")
                    new_lines.append(f"{indent}put(\"{key}\", {var_name})")
                else:
                    new_lines.append(block_lines[-1])
                    new_lines.append(f"{indent}put(\"{key}\", {var_name})")
                
                i = j
            else:
                new_lines.append(line)
        else:
            new_lines.append(line)
        i += 1
    
    with open(path, 'w') as f:
        f.write('\n'.join(new_lines))
    print(f"Fixed {path}")

# Verify
for path in [
    "engine/singbox/src/main/kotlin/com/novavpn/engine/singbox/SingboxConfigParser.kt",
    "engine/xray/src/main/kotlin/com/novavpn/engine/xray/XrayConfigParser.kt"
]:
    with open(path) as f:
        lines = f.readlines()
    issues = 0
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if re.match(r'put\("[^"]+",\s*(buildJson)', s):
            print(f"  REMAINING: {path}:{i}: {s[:70]}")
            issues += 1
    if issues == 0:
        print(f"  ✓ No inline buildJson in put()")
