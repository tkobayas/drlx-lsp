/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import * as net from "net";

// Import the language client, language client options and server options from VSCode language client.
import {LanguageClient, LanguageClientOptions, ServerOptions, StreamInfo} from 'vscode-languageclient/node';

let client: LanguageClient;

export function activate(context: vscode.ExtensionContext) {
    console.log('on activate, your extension "drlx"....');
    let serverOptions: ServerOptions | undefined = undefined;

    const DEBUG_MODE = process.env.LSDEBUG;

    console.log('DEBUG_MODE ' + DEBUG_MODE);

    if (DEBUG_MODE === 'true') {
        console.log('Starting in debug mode');
        let connectionInfo = {
            port: 9926,
            host: "127.0.0.1"
        };
        console.log('connectionInfo ' + connectionInfo);
        serverOptions = () => {
            // Connect to language server via socket
            let socket = net.connect(connectionInfo);
            let result: StreamInfo = {
                writer: socket,
                reader: socket
            };
            return Promise.resolve(result);
        };
    } else {
        console.log('Starting without debug');

        const javaHome = getJavaHome();

        let executable: string = `java`;

        if (javaHome) {
            // If java home is available, compose a path
            executable = path.join(javaHome, 'bin', 'java');
        } else {
            console.warn('java home is not found. Invoking java without path.');
        }

        // path to the launcher.jar
        let serverJar = path.join(__dirname, "..", 'lib', 'drlx-lsp-server-jar-with-dependencies.jar');
        if (fs.existsSync(serverJar)) {
            console.log(`${serverJar} exists`);
        } else {
            console.error(`${serverJar} does not exist : The extension won't work`);
            return;
        }
        const args: string[] = ['-jar', serverJar];

        serverOptions = {
            command: executable,
            args: [...args],
            options: {}
        };
    }

    if (serverOptions) {
        console.log('serverOptions ' + serverOptions);
        // Options to control the language client
        let clientOptions: LanguageClientOptions = {
            // Register the server for drlx documents
            documentSelector: [{scheme: 'file', language: 'drlx'}],
            synchronize: {
                // Notify the server about file changes to '.drlx files contained in the workspace
                fileEvents: vscode.workspace.createFileSystemWatcher('**/*.drlx')
            }
        };
        // Create the language client and start the client.
        client = new LanguageClient('DRLX', 'DRLX Language Server', serverOptions, clientOptions);
        
        // Add the client to subscriptions for proper cleanup
        context.subscriptions.push(client);
        
        // Start the client (this returns a Promise<void>)
        client.start();

        console.log('Congratulations, your extension "drlx" is now active!');
    }
}

// this method is called when your extension is deactivated
export function deactivate(): Thenable<void> | undefined {
    console.log('Your extension "drlx" is now deactivated!');
    if (!client) {
        return undefined;
    }
    return client.stop();
}

function getJavaHome(): string | undefined {

    let javaHome: string | undefined;

    javaHome = vscode.workspace.getConfiguration().get('java.home');
    if (javaHome) {
        console.log('java.home from workspace configuration : ' + javaHome);
        return javaHome;
    }

    // GHA_JAVA_HOME is to specify JAVA_HOME for Github Action (MacOS changes JAVA_HOME internally)
    javaHome = process.env.GHA_JAVA_HOME;
    if (javaHome) {
        console.log('GHA_JAVA_HOME from process env : ' + javaHome);
        return javaHome;
    }

    javaHome = process.env.JAVA_HOME;
    if (javaHome) {
        console.log('JAVA_HOME from process env : ' + javaHome);
        return javaHome;
    }

    console.log('java home is not found');
    return javaHome; // undefined
}