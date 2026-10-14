import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from structure_knowledge import RESOURCES, SOURCE, OUTPUT, build, project


class StructureKnowledgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = json.loads((RESOURCES / SOURCE).read_bytes())
        cls.by_id = {doc['id']: doc for doc in cls.source}

    def test_rebuild_is_deterministic_and_preserves_source_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'homecare').mkdir()
            for name in (SOURCE, OUTPUT, 'homecare/source-manifest.json'):
                (root / name).write_bytes((RESOURCES / name).read_bytes())
            before = (root / SOURCE).read_bytes()
            version = json.loads((root / 'homecare/source-manifest.json').read_bytes())['version']
            build(root)
            build(root, check=True)
            self.assertEqual((root / SOURCE).read_bytes(), before)
            self.assertEqual((root / OUTPUT).read_bytes(), (RESOURCES / OUTPUT).read_bytes())
            manifest = json.loads((root / 'homecare/source-manifest.json').read_bytes())
            self.assertEqual(manifest['version'], version)
            self.assertEqual(manifest['knowledgeProjection']['sourceSha256'], hashlib.sha256(before).hexdigest())
            with (root / SOURCE).open('ab') as file:
                file.write(b' ')
            with self.assertRaisesRegex(ValueError, 'Source hash'):
                build(root)

    def test_vibration_keeps_three_cause_branches_and_optional_feature_condition(self):
        result = project(self.by_id['B17-MFL71792818-topic_66-009'])
        self.assertEqual(result['symptoms'], ['소음 또는 진동이 심해요.'])
        branches = result['branches']['action_branches']
        self.assertEqual(len(branches), 3)
        self.assertIn('고정볼트', branches[0]['condition_summary'])
        self.assertEqual(branches[2]['feature_gate'], 'turbo_optional')
        self.assertIn('해당 기능이 실제 있는 모델', branches[2]['applicability_conditions'][0])
        self.assertNotIn('turbo_optional', result['searchText'])
        self.assertFalse(result['isActionApproved'])

    def test_empty_qa_does_not_erase_original_preconditions_steps_or_diagram_gate(self):
        source = copy.deepcopy(self.by_id['B17-MFL71995305-topic_39-procedure'])
        raw = json.loads(source['text'])
        raw['current_QA_review'] = {'preconditions': [], 'warnings': [], 'ordered_steps': [],
                                    'source_sha256': 'INTERNAL_QA_HASH', 'checked_at': 'INTERNAL_DATE'}
        source['text'] = json.dumps(raw)
        result = project(source)
        self.assertEqual(len(result['procedureSteps']), 2)
        self.assertIn('급수 밸브 차단 확인', result['preconditions'])
        self.assertTrue(result['constraints']['original']['requires_original_diagram_confirmation'])
        self.assertEqual(result['relationships'][0]['documentIds'], ['B18-MFL71995305-inlet-reassembly-supplement'])
        self.assertNotIn('INTERNAL_QA_HASH', result['searchText'])

    def test_unknown_fields_and_historical_variants_do_not_become_current_guidance(self):
        result = project(self.by_id['T3-001'])
        self.assertIn('escalate_when', result['unknownFields'])
        self.assertNotIn('조치해도 배수되지 않을 때', result['searchText'])
        self.assertIn('1 m 이하', result['searchText'])
        self.assertIn('화상 위험', result['warnings'][0])

    def test_all_records_exclude_wrapper_metadata_from_search_and_display(self):
        for source in self.source:
            result = project(source)
            for field in ('searchText', 'displayText'):
                self.assertNotIn('original_record_sha256', result[field], source['id'])
                self.assertNotIn('current_QA_review', result[field], source['id'])
                self.assertNotIn('source_topic_sha256', result[field], source['id'])
                self.assertNotIn(source['original_sha256'], result[field], source['id'])
                self.assertFalse(result[field].startswith('{'), source['id'])
            self.assertEqual(result['id'], source['id'])
            self.assertFalse(result['isActionApproved'])


if __name__ == '__main__':
    unittest.main()
