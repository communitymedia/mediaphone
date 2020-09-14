import os, sys
import glob
import re
import argparse
from xml.etree import ElementTree

# full path to the directory containing this script, which should be at [repository root]/fastlane/xmltofastlane.py
ROOT_PATH = os.path.dirname(os.path.realpath(__file__))

# relative path to tranlations, all in [ROOT_PATH]/[TRANSLATIONS_PATH]/values[locale]/[TRANSLATIONS_FILE].xml
TRANSLATIONS_PATH = '../MediaPhone/src/main/res'

# the name of the XML file in which the store listing translations are stored, *without* the .xml extension
TRANSLATIONS_FILE = 'store_listing'

# the fastlane data output path, which is transformed to: [ROOT_PATH]/[OUTPUT_PATH]/[locale]/[file].txt
# e.g., [repository root]/fastlane/metadata/android/en-US/[file].txt
OUTPUT_PATH = 'metadata/android'

# translate Android ISO 639 language codes to fastlane (languages not in this list default to the Android code without the leading dash)
LOCALE_MAP = {
	'': 'en-US',
	'-es': 'es-ES',
	'-fr': 'fr-FR',
	'-nl': 'nl-NL',
	'-pt': 'pt-PT',
	'-pl': 'pl-PL',
	'-ru': 'ru-RU'
}

# loaded from the default language file and used for all others unless overridden here or in an individual translation
APP_TITLE = None


def saveFile(content, directory, filename):
	if content is not None:
		outputFilePath = os.path.join(directory, filename)
		if not os.path.exists(directory):
			os.makedirs(directory)
		print('Writing to %s' % outputFilePath)
		with open(outputFilePath, 'w', encoding = 'utf-8') as outputFile:
			outputFile.write(content)


def getElementText(xmlRoot, elementSelector, lengthLimit = 0):
	element = xmlRoot.find(elementSelector)
	if element is not None and element.text is not None:
		text = element.text.strip().replace('\\\'', '\'').replace('\\\"', '\"') # trim and remove XML string escape characters
		if lengthLimit != 0 and len(text) > lengthLimit:
			print(('\033[93m' + 'Warning: Text length of %d is longer than limit of %d characters for selector %s:\n%s' + '\033[0m') % (len(text), lengthLimit, elementSelector, text))
		return text
	return None


def translateLocale(androidLocale, localeMap):
	if androidLocale in localeMap.keys():
		return localeMap[androidLocale]
	elif androidLocale is not None:
		return androidLocale[1:]
	return ''


def updateDefaultTitle(title):
	global APP_TITLE
	APP_TITLE = title


def processTranslation(xml, androidLocale):
	if os.path.isfile(xml):
		fastlaneLocale = translateLocale(androidLocale, LOCALE_MAP)
		fastlaneOutputPath = os.path.join(ROOT_PATH, OUTPUT_PATH, fastlaneLocale)

		xmlRoot = ElementTree.parse(xml).getroot()

		title = getElementText(xmlRoot, './/string[@name="title"]', 50)
		if APP_TITLE is None:
			updateDefaultTitle(title)
		if title is None:
			title = APP_TITLE
		saveFile(title, fastlaneOutputPath, 'title.txt')

		shortDescription = getElementText(xmlRoot, './/string[@name="short_description"]', 80)
		saveFile(shortDescription, fastlaneOutputPath, 'short_description.txt')

		# Google Play supports only very limited HTML characters (https://stackoverflow.com/a/18746972/), hence the use of <br /> rather than <p>
		fullDescription = getElementText(xmlRoot, './/string[@name="full_description"]', 4000)
		saveFile(fullDescription, fastlaneOutputPath, 'full_description.txt')


if __name__ == '__main__':
	# make sure we don't forget to create a local changelog file
	parser = argparse.ArgumentParser()
	parser.add_argument('-v', '--versionCode', type = int, required = True)
	args = parser.parse_args()

	defaultLocale = translateLocale('', LOCALE_MAP)
	changelogOutputPath = os.path.join(ROOT_PATH, OUTPUT_PATH, defaultLocale, 'changelogs', '%d.txt' % args.versionCode)
	if os.path.exists(changelogOutputPath):
		print('Changelog file found (not translated):\n%s\n' % changelogOutputPath)
	else:
		print(('\033[91m' + 'Error: No fastlane changelog file found for versionCode %d; expected at:\n%s' + '\033[0m') % (args.versionCode, changelogOutputPath))
		sys.exit(1)

	print('Generating localised fastlane description files')
	path = os.path.join(ROOT_PATH, TRANSLATIONS_PATH, 'values*/' + TRANSLATIONS_FILE + '.xml')
	localeRegex = re.compile(r'.*[/\\]values([^/\\]*)[/\\]' + TRANSLATIONS_FILE + '\.xml', re.IGNORECASE)
	for translation in glob.glob(path):
		locale = localeRegex.sub(r'\1', translation)
		print('\nProcessing locale %s' % (locale if len(locale) > 0 else '[default]'))
		processTranslation(translation, locale)
