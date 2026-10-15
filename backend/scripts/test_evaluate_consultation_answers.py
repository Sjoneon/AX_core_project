import unittest
from evaluate_consultation_answers import assess


class AnswerReviewValidationTests(unittest.TestCase):
    def review(self, **changes):
        return {'scores': dict.fromkeys(('direct_answer', 'relevance', 'grounding', 'next_action', 'clarity'), 2),
                'critical_errors': [], 'missing_points': [], 'reason': '실제 답변 확인',
                'answer_excerpts': ['공식 점검을 받아 주세요.'], **changes}

    def test_classification_or_tool_success_cannot_replace_direct_answer_and_next_action(self):
        review = self.review()
        review['scores']['direct_answer'] = 1
        self.assertEqual(assess(review, ['공식 점검을 받아 주세요.'])['verdict'], 'FAIL')
        review['scores']['direct_answer'] = 2
        review['critical_errors'] = ['미승인 조치']
        self.assertEqual(assess(review, ['공식 점검을 받아 주세요.'])['verdict'], 'FAIL')

    def test_fabricated_quotes_and_invalid_scores_do_not_become_a_success(self):
        with self.assertRaises(ValueError):
            assess(self.review(), ['어떤 증상인가요?'])
        review = self.review()
        review['scores']['grounding'] = True
        with self.assertRaises(ValueError):
            assess(review, ['공식 점검을 받아 주세요.'])

    def test_explicit_data_shortage_with_a_useful_next_action_can_pass(self):
        result = assess(self.review(), ['확인된 부품 근거가 부족합니다. 공식 점검을 받아 주세요.'])
        self.assertEqual(result['verdict'], 'PASS')


if __name__ == '__main__':
    unittest.main()
