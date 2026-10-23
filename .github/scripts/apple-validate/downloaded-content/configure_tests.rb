require 'xcodeproj'

project = Xcodeproj::Project.open('iosApp/iosApp.xcodeproj')
app = project.targets.find { |target| target.name == 'iosApp' }
raise 'Missing iosApp target' unless app
tests = project.new_target(:ui_test_bundle, 'DownloadedContentUITests', :ios, '17.0')
tests.add_dependency(app)
source = project.main_group.new_file('../.github/scripts/apple-validate/downloaded-content/DownloadedContentUITests.swift')
tests.source_build_phase.add_file_reference(source)
tests.build_configurations.each do |config|
  config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'com.yamibo.bbs.downloadqa.uitests'
  config.build_settings['PRODUCT_MODULE_NAME'] = 'DownloadedContentUITests'
  config.build_settings['PRODUCT_NAME'] = 'DownloadedContentUITests'
  config.build_settings['SWIFT_VERSION'] = '5.0'
  config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
  config.build_settings['TEST_TARGET_NAME'] = app.name
  config.build_settings['TARGETED_DEVICE_FAMILY'] = '1,2'
  config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_build_target(tests)
scheme.add_test_target(tests)
scheme.set_launch_target(app)
scheme.save_as('iosApp/iosApp.xcodeproj', 'DownloadedContentQA', true)
