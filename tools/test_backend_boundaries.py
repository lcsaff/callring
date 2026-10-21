import unittest
from pathlib import Path
from check_backend_boundaries import ROOT, contract_inventory, compatible_inventory, has_presentation_literal


class BoundaryPolicyTest(unittest.TestCase):
    def test_contract_inventory_detects_signature_changes(self):
        files = {'common/Result.kt': 'package contracts\ndata class Result(val number: String)'}
        inventory = contract_inventory(files)
        self.assertTrue(compatible_inventory(files, inventory))
        self.assertFalse(compatible_inventory({'common/Result.kt': files['common/Result.kt'].replace('String', 'Long')}, inventory))
        self.assertFalse(compatible_inventory({}, inventory))

    def test_protocol_metadata_exception_does_not_allow_ordinary_ui_messages(self):
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'ToolDefinition(73,"discover",schema,false,"发现工具")'))
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'fun saved() = "保存成功"'))
        self.assertTrue(has_presentation_literal(Path('Another.kt'), 'val message = "保存成功"'))

    def test_inventory_ignores_comments_but_preserves_defaults_and_new_symbols(self):
        files = {'A.kt': 'data class A(val value: Long = 1)'}
        inventory = contract_inventory(files)
        self.assertTrue(compatible_inventory({'A.kt': '// comment\n' + files['A.kt']}, inventory))
        self.assertFalse(compatible_inventory({'A.kt': files['A.kt'].replace('= 1', '= 2')}, inventory))
        self.assertFalse(compatible_inventory(dict(files, **{'B.kt': 'interface B'}), inventory))

    def test_localized_metadata_does_not_exempt_the_rest_of_the_line_or_region(self):
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'ToolDefinition(73,"discover",schema,false,"发现工具"); val unrelated="保存成功"'))
        self.assertTrue(has_presentation_literal(Path('ToolRegistry.kt'), 'ToolDefinition(73,"发现工具",schema,false,"metadata")'))
        self.assertTrue(has_presentation_literal(Path('CapabilityCatalog.kt'), 'private val purposes=listOf("发现工具")\nfun toolPurpose()=Unit'))
        self.assertTrue(has_presentation_literal(Path('CapabilityCatalog.kt'), 'private val purposes=listOf("发现工具");val unrelated="保存成功"\nfun toolPurpose()=Unit'))
        self.assertTrue(has_presentation_literal(Path('CapabilityCatalog.kt'), 'private val purposes=listOf(run { val label="保存成功"; label })\nfun toolPurpose()=Unit'))


class NamedOperationMetadataTest(unittest.TestCase):
    def test_only_literal_description_in_real_catalog_definition_is_metadata(self):
        row='OperationDefinition(id=OperationId.DISCOVER_TOOLS, name="discover_tools", description="发现工具")'
        self.assertFalse(has_presentation_literal(Path('OperationCatalog.kt'),row))
        self.assertTrue(has_presentation_literal(Path('Another.kt'),row))
        self.assertTrue(has_presentation_literal(Path('OperationCatalog.kt'),row+';val message="保存成功"'))
        self.assertTrue(has_presentation_literal(Path('OperationCatalog.kt'),row.replace('name="discover_tools"','name="发现工具"')))
        self.assertTrue(has_presentation_literal(Path('OperationCatalog.kt'),row.replace('description="发现工具"','description=run { val label="保存成功";label }')))


class ThemePresetMetadataTest(unittest.TestCase):
    def test_only_shared_preset_display_name_is_metadata(self):
        row = 'package com.greatwall.callring.contracts.settings\nThemePreset("unicom_red", "联通红", 0xE60012)'
        self.assertFalse(has_presentation_literal(Path('ThemePresets.kt'), row))
        self.assertTrue(has_presentation_literal(Path('Another.kt'), row))
        self.assertTrue(has_presentation_literal(Path('ThemePresets.kt'), row.replace('contracts.settings', 'application.settings')))
        self.assertTrue(has_presentation_literal(Path('ThemePresets.kt'), row + '; val message="保存成功"'))
        self.assertTrue(has_presentation_literal(Path('ThemePresets.kt'), row.replace('"unicom_red"', '"联通红"')))
        self.assertTrue(has_presentation_literal(Path('ThemePresets.kt'), row.replace('"联通红"', 'run { val label="保存成功"; label }')))

if __name__ == '__main__':
    unittest.main()
