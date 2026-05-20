import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

/**
 * Converts LCOV coverage format to a basic JaCoCo XML format.
 * 
 * Usage: node lcov_to_jacoco.mjs <input_lcov_file> <output_xml_file>
 */

const inputFile = process.argv[2];
const outputFile = process.argv[3];

if (!inputFile || !outputFile) {
    console.error('Usage: node lcov_to_jacoco.mjs <input_lcov_file> <output_xml_file>');
    process.exit(1);
}

async function convert() {
    const fileStream = fs.createReadStream(inputFile);
    const rl = readline.createInterface({
        input: fileStream,
        crlfDelay: Infinity
    });

    let xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
    xml += '<report name="LCOV to JaCoCo">\n';

    // We'll group everything in a single virtual package to keep it simple,
    // as SonarCloud identifies files by their relative path from the project root.
    xml += '  <package name="src">\n';

    let currentFile = null;
    let lines = [];
    let coveredLines = 0;
    let missedLines = 0;

    for await (const line of rl) {
        if (line.startsWith('SF:')) {
            currentFile = line.substring(3).trim();
            lines = [];
            coveredLines = 0;
            missedLines = 0;
        } else if (line.startsWith('DA:')) {
            const parts = line.substring(3).split(',');
            const lineNum = parseInt(parts[0], 10);
            const count = parseInt(parts[1], 10);
            lines.push({ nr: lineNum, ci: count > 0 ? 1 : 0, mi: count > 0 ? 0 : 1 });
            if (count > 0) coveredLines++; else missedLines++;
        } else if (line === 'end_of_record') {
            if (currentFile) {
                // Determine source file name and internal path
                const sourceFileName = path.basename(currentFile);
                xml += `    <sourcefile name="${currentFile}">\n`;
                for (const l of lines) {
                    xml += `      <line nr="${l.nr}" mi="${l.mi}" ci="${l.ci}"/>\n`;
                }
                xml += `      <counter type="LINE" missed="${missedLines}" covered="${coveredLines}"/>\n`;
                xml += '    </sourcefile>\n';
            }
            currentFile = null;
        }
    }

    xml += '  </package>\n';
    xml += '  <counter type="LINE" missed="0" covered="0"/>\n'; // Global counter (optional)
    xml += '</report>';

    fs.writeFileSync(outputFile, xml);
    console.log(`Successfully converted ${inputFile} to ${outputFile}`);
}

convert().catch(err => {
    console.error('Conversion failed:', err);
    process.exit(1);
});
