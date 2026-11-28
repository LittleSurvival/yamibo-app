require 'xcodeproj'

project = Xcodeproj::Project.open('iosApp/iosApp.xcodeproj')
app = project.targets.find { |target| target.name == 'iosApp' } or raise 'Missing iosApp target'
tests = project.new_target(:ui_test_bundle, 'CrashReportUITests', :ios, '17.0')
tests.add_dependency(app)
source = project.main_group.new_file('../.github/scripts/apple-validate/crash-report/CrashReportUITests.swift')
tests.source_build_phase.add_file_reference(source)
tests.build_configurations.each do |config|
  config.build_settings.merge!(
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.yamibo.bbs.crashqa.uitests',
    'PRODUCT_MODULE_NAME' => 'CrashReportUITests',
    'PRODUCT_NAME' => 'CrashReportUITests',
    'SWIFT_VERSION' => '5.0',
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'TEST_TARGET_NAME' => app.name,
    'TARGETED_DEVICE_FAMILY' => '1,2',
    'CODE_SIGNING_ALLOWED' => 'NO'
  )
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_build_target(tests)
scheme.add_test_target(tests)
scheme.set_launch_target(app)
scheme.save_as('iosApp/iosApp.xcodeproj', 'CrashReportQA', true)
