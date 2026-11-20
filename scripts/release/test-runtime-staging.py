"""Regression checks for the native runtime staging contract."""
import ast
import pathlib
import tempfile
import unittest

HERE = pathlib.Path(__file__).parent

class RuntimeStagingTests(unittest.TestCase):
    def test_incomplete_stage_is_not_reused(self):
        tree = ast.parse((HERE / 'prepare-runtime.py').read_text())
        assignment = next(n for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'backends_complete' for t in n.targets))
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory)
            def complete():
                return eval(compile(ast.Expression(assignment.value), '<check>', 'eval'), {'platform': 'darwin', 'target': target})
            self.assertFalse(complete())
            (target / 'lib').mkdir(); (target / 'bin').mkdir()
            (target / 'lib/libggml-metal.so').touch()
            self.assertFalse(complete())
            (target / 'lib/libggml-cpu-apple_m4.so').touch()
            self.assertFalse(complete())
            (target / 'bin/llama-server-native').touch()
            self.assertTrue(complete())

if __name__ == '__main__': unittest.main()
