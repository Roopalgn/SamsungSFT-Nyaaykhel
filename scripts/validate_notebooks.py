import json, pathlib

for nb_rel in ['colab/00_data_collection.ipynb', 'colab/01_pose_extraction_test.ipynb']:
    p = pathlib.Path(nb_rel)
    try:
        data = json.loads(p.read_text(encoding='utf-8'))
        cells = data.get('cells', [])
        errors = []
        for cell in cells:
            if cell.get('cell_type') == 'code':
                src = ''.join(cell.get('source', []))
                try:
                    compile(src, cell.get('id', '?'), 'exec')
                except SyntaxError as e:
                    errors.append(f"  Cell '{cell.get('id','?')}': {e}")
        if errors:
            print(f'SYNTAX ERRORS in {nb_rel}:')
            for err in errors:
                print(err)
        else:
            print(f'OK  {nb_rel}  ({len(cells)} cells, all code cells compile)')
    except json.JSONDecodeError as e:
        print(f'JSON ERROR in {nb_rel}: {e}')
