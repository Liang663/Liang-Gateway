import {spawnSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const base=dirname(fileURLToPath(import.meta.url));
const cli='C:/Users/xxx/.codex/skills/archify/bin/archify.mjs';
const files=['01-system.architecture','02-chat.workflow','03-mcp.sequence','04-assets.dataflow','05-sse.lifecycle'];
const action=process.argv[2]||'validate';
for(const stem of files.filter((s,i)=>!process.argv[3]||process.argv[3].split(',').includes(String(i+1)))){
 const type=stem.split('.')[1],json=join(base,stem+'.json'),html=join(base,stem+'.html');
 const args=action==='visual-check'?[action,html,'--json']:[action,type,json,...(action==='deliver'?[html]:[]),'--quality','showcase','--json',...(type==='architecture'?['--repo-root','D:/java/ideaProjects/Liang-Gateway']:[])];
 const r=spawnSync(process.execPath,[cli,...args],{encoding:'utf8',maxBuffer:16*1024*1024});
 let receipt;try{receipt=JSON.parse(r.stdout)}catch{receipt={stdout:r.stdout,stderr:r.stderr}};
 if(action!=='visual-check')writeFileSync(join(base,stem+'.'+action+'.json'),JSON.stringify(receipt,null,2)+'\n');
 console.log(JSON.stringify({stem,exit:r.status,ok:receipt.ok,status:receipt.status,error:receipt.error,validation:receipt.validation,artifact:receipt.artifact,diagnostics:receipt.diagnostics}));
}
