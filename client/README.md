# DRLX Language Editor Extension

This extension provides syntax highlighting and code completion for DRLX files.

## Features

- Syntax highlighting
- Code completion
- Rule structure support
- Pattern and consequence completion

## How to build

Under `client` directory, run:

```bash
npm install
npm run pack:dev
```

vsix file will be generated in `dist` directory.

## Known Issues

- Code completion may suggest words that are not valid in the current context
- This is alpha version. If you find any issues, please report them in the project issues tracker

## File Extension

- `.drlx` - DRLX rule files