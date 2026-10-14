#!/usr/bin/env python3
"""Build a deterministic search/display projection from the preserved B30 wrappers.

python -I backend/scripts/structure_knowledge.py
python -I backend/scripts/structure_knowledge.py --check

knowledge.json is the unchanged internal source, not a customer-facing document.
This does not collect missing B30 records or approve procedures.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

RESOURCES = Path(__file__).resolve().parents[1] / 'src/main/resources'
SOURCE = 'homecare/knowledge.json'
OUTPUT = 'homecare/knowledge-structured.json'
VERSION = 'B30-COST-V3-20261014-R4'

# Explicit semantic fields only; never flatten a wrapper or arbitrary QA metadata.
GROUPS = {
    'symptoms': ('symptom', 'symptom_label', 'meaning', 'danger_symptom', 'trigger', 'display_meaning'),
    'causes': ('cause', 'causes'),
    'actionDescriptions': ('action_summary', 'user_action', 'immediate_action'),
    'preconditions': ('preconditions', 'input_preconditions', 'prerequisites', 'applicability_conditions',
                      'normal_operation_conditions', 'usage_conditions'),
    'warnings': ('warnings', 'safety_note', 'safety_priority_note', 'reference_safety_notes',
                 'safety_constraints', 'safety_conditions', 'prohibits', 'use_limitation', 'material_restrictions',
                 'conflict_summary'),
}
BRANCHES = ('action_branches', 'cause_branches', 'branches', 'conditional_subcases',
            'model_feature_subcases', 'alternative_or_component_paths', 'component_branches',
            'excluded_optional_subcases', 'excluded_optional_branches', 'excluded_action_branches',
            'excluded_branches', 'withheld_or_separated_branches', 'branch_decisions', 'component_decisions')
CONSTRAINTS = ('installation_constraint', 'feature_branch_gates', 'feature_scope', 'safety_gate',
               'power_state', 'power_state_requirement', 'physical_cleaning_power_state',
               'programmatic_cleaning_power_state', 'phase_power_requirements', 'conditional_exclusions',
               'source_scope_exclusions', 'use_exceptions', 'approval_scope', 'source_applicability',
               'field_limitations', 'execution_policy', 'safety_override', 'routing_rule')
TEXT_FIELDS = set(sum(GROUPS.values(), ())) | {
    'condition', 'condition_summary', 'selfcare', 'instruction', 'text', 'action', 'description',
    'reason', 'subcase', 'warning', 'requirement', 'safety_note', 'procedure_title',
    'component_name_in_source', 'evidence_terms', 'specific_drying_option', 'normal_reference',
}
SOURCE_GROUPS = ('additional_sources', 'additional_safety_sources', 'supporting_sources',
                 'supplemental_sources', 'related_procedure_sources', 'exact_manual_sources')


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def unique(values):
    result = []
    for value in values:
        if value not in result:
            result.append(value)
    return result


def sentences(value):
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        return [text for item in value for text in sentences(item)]
    return []


def gate(key, value):
    return (key in CONSTRAINTS or key.startswith(('requires_', 'not_', 'prohibit', 'forbidden_', 'excluded_'))
            or key.endswith(('_required', '_allowed', '_forbidden', '_not_inferred'))
            or isinstance(value, bool))


def semantic(value):
    """Preserve branch boundaries and all gates; remove provenance/history from the projection."""
    if isinstance(value, list):
        return [semantic(item) for item in value]
    if isinstance(value, dict):
        unknown = value.get('unknown_fields', [])
        return {key: semantic(item) for key, item in value.items()
                if key not in unknown and 'sha256' not in key and not key.endswith('_at')
                and key not in ('source_branch_index', 'unknown_fields', 'missing_field_reasons',
                                'field_verified_at', 'field_source_urls', 'runtime_implementation_status',
                                'review_issue_ids', 'source_variants', 'synthetic_fields', 'DOM_selector', 'locator')}
    return value


def text_leaves(value):
    """Only human explanatory fields become prose, never IDs, gates, hash values or JSON keys."""
    if isinstance(value, list):
        return [text for item in value for text in text_leaves(item)]
    if isinstance(value, dict):
        return [text for key, item in value.items() if key in TEXT_FIELDS or key in BRANCHES
                for text in text_leaves(item)]
    return sentences(value)


def selected(record, keys):
    content = record.get('content', {})
    unknown = record.get('unknown_fields', [])
    return [(path, value) for key in keys if key not in unknown
            for path, value in ((f'record.{key}', record.get(key)),
                                (f'record.content.{key}', content.get(key)))]


def locator(value):
    return {key: value[key] for key in ('section', 'topic_id', 'topic_title', 'block_index', 'page') if key in value}


def project(doc):
    wrapper = json.loads(doc['text'])
    if wrapper['id'] != doc['id'] or wrapper['record']['id'] != doc['id']:
        raise ValueError(f"Wrapper ID mismatch: {doc['id']}")
    record = wrapper['record']
    result = {key: doc[key] for key in ('id', 'category', 'title', 'topic', 'recordType', 'errorCodes', 'baseDate')}
    result['schemaVersion'] = 1
    origins = {}
    for name, keys in GROUPS.items():
        fields = selected(record, keys)
        result[name] = unique([text for _, value in fields for text in sentences(value)])
        origins[name] = [path for path, value in fields if sentences(value)]
    # Unverified/synthetic user phrases are deliberately not promoted to manufacturer evidence.
    result['branches'] = {key: semantic(record[key]) for key in BRANCHES if record.get(key)}
    result['procedureSteps'] = semantic(record.get('ordered_steps', []))
    for _, value in selected(record, ('steps',)):
        if value:
            result['procedureSteps'].append({'instruction': value})
    result['constraints'] = {}
    for name, value in (('original', record), ('qa', wrapper.get('current_QA_review') or {}),
                        ('applicabilityUpdate', wrapper.get('latest_applicability_update') or {})):
        result['constraints'][name] = {key: semantic(value[key]) for key in value if gate(key, value[key])}
        # A present QA condition supplements the original; an empty QA array never erases it.
        if name == 'qa':
            for group in ('preconditions', 'warnings'):
                result[group] = unique(result[group] + [text for key in GROUPS[group]
                                                        for text in sentences(value.get(key))])
                origins[group] += [f'current_QA_review.{key}' for key in GROUPS[group] if value.get(key)]
    result['relationships'] = []
    for link in wrapper.get('procedure_supplements', []):
        ids = unique(([link['preferred_fact_id']] if link.get('preferred_fact_id') else [])
                     + link.get('new_procedure_fact_ids', []))
        result['relationships'].append({'relation': 'PROCEDURE_SUPPLEMENT', 'documentIds': ids,
                                        'conditions': sentences(link.get('remaining_condition'))})
    if record.get('amends_fact_id'):
        result['relationships'].append({'relation': 'AMENDS', 'documentIds': [record['amends_fact_id']], 'conditions': []})
    for key, value in record.items():
        if key.endswith(('_fact_id', '_fact_ids', '_ref', '_refs')) and key != 'amends_fact_id':
            ids = [v for v in sentences(value) if v.startswith(('B', 'T')) and re.fullmatch(r'[\w.-]+', v)]
            if ids:
                result['relationships'].append({'relation': key, 'documentIds': ids, 'conditions': []})
    update = wrapper.get('latest_applicability_update') or {}
    for key in ('review_fact_id', 'preferred_policy_fact_ids', 'preferred_fact_ids'):
        if update.get(key):
            result['relationships'].append({'relation': 'APPLICABILITY_UPDATE', 'documentIds': sentences(update[key]),
                                            'conditions': sentences(update.get('update_scope'))})
    # Role and locator remain attached to each URL; historical source_variants are internal only.
    result['sources'] = [{'url': url, 'role': 'PRIMARY', 'locator': locator(record.get('source_locator', {}))}
                         for url in doc['sourceUrls']]
    for group in SOURCE_GROUPS:
        for source in record.get(group, []):
            if isinstance(source, dict) and source.get('source_url', '').startswith('https://'):
                result['sources'].append({'url': source['source_url'], 'role': group,
                                          'locator': locator(source.get('source_locator', {}))})
    result['sources'] = unique(result['sources'])
    result['modelApplicability'] = wrapper['effective_model_lists'] or {'original_model_codes': doc['modelCodes']}
    result['fieldOrigins'] = origins
    result['unknownFields'] = record.get('unknown_fields', [])
    result['referenceDetails'] = {key: semantic(record[key]) for key in (
        'verified_product_specs', 'selected_spec_fields', 'verified_part_compatibility',
        'numeric_parameters', 'parameters', 'documented_paid_scenarios', 'source_conflict',
        'conflicting_entries', 'conflict') if record.get(key)}
    # Preserve product/policy fields as typed reference data, outside symptom/action prose.
    content = record.get('content', {})
    if doc['recordType'] in ('T1_models', 'T5_warranty_fees'):
        result['referenceDetails']['content'] = {key: value for key, value in content.items()
            if key not in record.get('unknown_fields', []) and key not in (
                'collected_at', 'source_url', 'cross_check', 'note', 'release_source')}
    result['displayTitle'] = doc['title']
    if doc['title'] == doc['id']:
        result['displayTitle'] = (record.get('title') or record.get('procedure_title')
            or record.get('source_locator', {}).get('topic_title')
            or content.get('product_name') or content.get('model_name')
            or {'product_specification': '제품 사양', 'part_compatibility_reference': '부품 호환 참고',
                'source_applicability_review': '모델 적용 조건 참고', 'source_conflict_review': '출처 확인 필요',
                'T5_warranty_fees': '보증·유상 서비스 조건 참고'}.get(doc['recordType'], '제조사 참고 자료'))
    # Keep candidate actions separate from display prose. Execution requires approved-plans.json.
    prose = unique([result['displayTitle']] + result['symptoms'] + result['causes'])
    display = list(prose)
    if result['preconditions']:
        display.append('적용 조건: ' + ' / '.join(result['preconditions']))
    if result['warnings']:
        display.append('주의사항: ' + ' / '.join(result['warnings']))
    result['displayText'] = '\n'.join(display)
    search = prose + result['actionDescriptions'] + result['preconditions'] + result['warnings']
    search += text_leaves(result['branches']) + text_leaves(result['procedureSteps'])
    specs = record.get('verified_product_specs', {})
    search += [f"{item['field']}: {item['value']}" for item in specs.get('selected_fields', [])
               if 'field' in item and 'value' in item]
    compatibility = record.get('verified_part_compatibility', {})
    search += sentences(compatibility.get('part_title')) + sentences(compatibility.get('part_number'))
    if doc['recordType'] == 'T1_models':
        search += [text for key in ('model_name', 'product_name', 'sub_type') for text in sentences(content.get(key))]
    result['searchText'] = '\n'.join(unique(search))
    result['isActionApproved'] = False
    return result


def build(resources=RESOURCES, check=False):
    manifest_path = resources / 'homecare/source-manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    source_bytes = (resources / SOURCE).read_bytes()
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    if source_hash != manifest['files'][SOURCE]:
        raise ValueError('Source hash differs from the verified manifest; refusing to project it.')
    docs = json.loads(source_bytes)
    output = [project(doc) for doc in docs]
    if len({doc['id'] for doc in output}) != len(docs):
        raise ValueError('Duplicate source IDs')
    data = encode(output)
    info = {'schemaVersion': 1, 'sourceFile': SOURCE, 'sourceSha256': source_hash,
            'outputFile': OUTPUT, 'generator': 'backend/scripts/structure_knowledge.py',
            'recordCount': len(output), 'byCategory': {}, 'byRecordType': {}}
    for doc in output:
        for field, key in (('category', 'byCategory'), ('recordType', 'byRecordType')):
            info[key][doc[field]] = info[key].get(doc[field], 0) + 1
    manifest['version'] = VERSION
    manifest['structuredKnowledgeCount'] = len(output)
    manifest['knowledgeProjection'] = info
    manifest['files'][OUTPUT] = hashlib.sha256(data).hexdigest()
    if check:
        if (resources / OUTPUT).read_bytes() != data or manifest_path.read_bytes() != encode(manifest):
            raise ValueError('Projection or manifest is stale; run structure_knowledge.py.')
    else:
        (resources / OUTPUT).write_bytes(data)
        manifest_path.write_bytes(encode(manifest))
    print(f'{VERSION}: {len(output)} structured records; source bytes unchanged; check={check}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    build(check=parser.parse_args().check)
