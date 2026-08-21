import json, pathlib

for nb_rel in [
    'colab/00_data_collection.ipynb',
    'colab/01_pose_extraction_test.ipynb',
    'colab/02_dataset_builder.ipynb',
    'colab/03_train_classifier.ipynb',
]:
    p = pathlib.Path(nb_rel)
    try:
        data = json.loads(p.read_text(encoding='utf-8'))
        cells = data.get('cells', [])
        errors = []
        for cell in cells:
            if cell.get('cell_type') == 'code':
                src = ''.join(cell.get('source', []))
                # Skip cells that use Jupyter shell magic (!pip, !apt, etc.)
                # These are valid in Colab but not in plain Python compile()
                if src.strip().startswith('!'):
                    continue
                # Strip leading shell-magic lines from mixed cells
                non_magic_lines = [l for l in src.splitlines(keepends=True)
                                   if not l.lstrip().startswith('!')]
                src_to_check = ''.join(non_magic_lines).strip()
                if not src_to_check:
                    continue
                try:
                    compile(src_to_check, cell.get('id', '?'), 'exec')
                except SyntaxError as e:
                    errors.append(f"  Cell '{cell.get('id','?')}': {e}")
        if errors:
            print(f'SYNTAX ERRORS in {nb_rel}:')
            for err in errors:
                print(err)
        else:
            print(f'OK  {nb_rel}  ({len(cells)} cells)')
    except json.JSONDecodeError as e:
        print(f'JSON ERROR in {nb_rel}: {e}')
