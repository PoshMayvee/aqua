import type {FunctionCallDef, ServiceDef} from "@fluencelabs/fluence/dist/internal/compilerSupport/v3impl/interface"

export class AquaConfig {
    constructor(logLevel: string, constants: string[], noXor: boolean, noRelay: boolean);

    logLevel?: string
    constants?: string[]
    noXor?: boolean
    noRelay?: boolean
}

export class AquaFunction {
    funcDef: FunctionCallDef
    script: string
}

export class CompilationResult {
    services: Record<string, ServiceDef>
    functions: Record<string, AquaFunction>
    functionCall?: AquaFunction
    errors: string[]
}

export class Input {
    constructor(input: string);

    input: string
}

export class Path {
    constructor(path: string);

    path: string
}

export class Call {
    constructor(functionCall: string,
                arguments: any,
                input: Input | Path);

    functionCall: string
    arguments: any
    input: Input | Path
}

export class Compiler {
    compile(input: Input | Path | Call, imports: string[], config?: AquaConfig): Promise<CompilationResult>;
}

export var Aqua: Compiler;