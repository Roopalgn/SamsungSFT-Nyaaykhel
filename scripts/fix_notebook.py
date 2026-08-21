import json, pathlib, sys

nb_path = pathlib.Path(r'c:\Users\roopa\OneDrive\Desktop\hackathons\SamsungSFT-Nyaaykhel\colab\01_pose_extraction_test.ipynb')
nb = json.loads(nb_path.read_text(encoding='utf-8'))

# Fix the bad line in cell id='exit-gate'
BAD = "    print()')\n"
GOOD = "    print()\n"

fixed_count = 0
for cell in nb['cells']:
    if cell.get('id') == 'exit-gate':
        src = cell['source']
        new_src = []
        for line in src:
            if line == BAD:
                new_src.append(GOOD)
                fixed_count += 1
            else:
                new_src.append(line)
        cell['source'] = new_src
        break

nb_path.write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding='utf-8')
print(f"Done. Fixed {fixed_count} line(s).")
