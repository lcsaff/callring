import unittest
from pathlib import Path
from check_backend_boundaries import ROOT, compatible_v1, has_presentation_literal


class BoundaryPolicyTest(unittest.TestCase):
    def test_optional_additions_do_not_hide_a_changed_or_required_v1_field(self):
        contract = (ROOT / 'docs/contracts/CallringUiApi.kt').read_text(encoding='utf-8')
        actual = (ROOT / 'backend/src/main/kotlin/com/greatwall/callring/application/api/CallringUiApi.kt').read_text(encoding='utf-8')
        self.assertTrue(compatible_v1(actual, contract))
        self.assertFalse(compatible_v1(actual.replace('val number: String', 'val number: Long'), contract))
        self.assertFalse(compatible_v1(actual.replace('val aiFailure: ConversationFailure? = null', 'val aiFailure: ConversationFailure'), contract))

    def test_protocol_metadata_exception_does_not_allow_ordinary_ui_messages(self):
        self.assertFalse(has_presentation_literal(Path('ToolRegistry.kt'), 'ToolDefinition(73,"discover",schema,false,"发现工具")'))
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'fun saved() = "保存成功"'))
        self.assertTrue(has_presentation_literal(Path('Another.kt'), 'val message = "保存成功"'))

    def test_optional_fields_are_allowed_only_in_their_approved_constructor_suffix(self):
        contract = (ROOT / 'docs/contracts/CallringUiApi.kt').read_text(encoding='utf-8')
        actual = (ROOT / 'backend/src/main/kotlin/com/greatwall/callring/application/api/CallringUiApi.kt').read_text(encoding='utf-8')
        self.assertFalse(compatible_v1(actual.replace('val pageSize: Int = 50)', 'val pageSize: Int = 50,val aiFailure:ConversationFailure?=null)'), contract))
        self.assertFalse(compatible_v1(actual.replace('val baseline:CallerDraft?=null,','val baseline:CallerDraft?=null,val baseline:CallerDraft?=null,'), contract))
        self.assertFalse(compatible_v1(actual.replace('val baseline:CallerDraft?=null,','val baseline:CallerDraft?=CallerDraft(),'), contract))

    def test_localized_metadata_does_not_exempt_the_rest_of_the_line_or_region(self):
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'ToolDefinition(73,"discover",schema,false,"发现工具"); val unrelated="保存成功"'))
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'ToolDefinition(73,"发现工具",schema,false,"metadata")'))
        self.assertFalse(has_presentation_literal(Path('CapabilityCatalog.kt'), 'private val purposes=listOf("发现工具")\nfun toolPurpose()=Unit'))
        self.assertTrue(has_presentation_literal(Path('CapabilityCatalog.kt'), 'private val purposes=listOf("发现工具");val unrelated="保存成功"\nfun toolPurpose()=Unit'))
        self.assertTrue(has_presentation_literal(Path('CapabilityCatalog.kt'), 'private val purposes=listOf(run { val label="保存成功"; label })\nfun toolPurpose()=Unit'))


if __name__ == '__main__':
    unittest.main()
