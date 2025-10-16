/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

import * as vscode from 'vscode';
import * as assert from 'assert';
import { getDocUri, activate } from './helper';

suite('Completion tests', () => {
	const docUri = getDocUri('incomplete.drlx');

	test('Completes "rule" inside class', async () => {
		await testCompletion(docUri, new vscode.Position(1, 2), {
			items: [
				{ label: 'rule', kind: vscode.CompletionItemKind.Keyword }
			]
		});
	}).timeout(20000); // increase timeout from the default 2000ms because helper.activate waits 2000ms for server startup
});

async function testCompletion(
	docUri: vscode.Uri,
	position: vscode.Position,
	expectedCompletionList: vscode.CompletionList
) {
	await activate(docUri);

	// Wait a bit more to ensure server is fully ready
	await new Promise(resolve => setTimeout(resolve, 1000));

	// Executing the command `vscode.executeCompletionItemProvider` to simulate triggering completion
	const actualCompletionList = (await vscode.commands.executeCommand(
		'vscode.executeCompletionItemProvider',
		docUri,
		position
	)) as vscode.CompletionList;

	console.log('actualCompletionList items count: ' + actualCompletionList.items.length);
	if (actualCompletionList.items.length > 0) {
		console.log('First few items: ' + actualCompletionList.items.slice(0, 5).map(item => item.label).join(', '));
	}

	assert.ok(actualCompletionList.items.length >= expectedCompletionList.items.length);

	expectedCompletionList.items.forEach((expectedItem) => {
		const actualItem = actualCompletionList.items.find(item => item.label === expectedItem.label);
		assert(actualItem);
		assert.strictEqual(actualItem.kind, expectedItem.kind);
	});
}
