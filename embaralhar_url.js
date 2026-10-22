// Embaralha o endereco do servidor para colocar em app/src/main/res/values/strings.xml (game_url).
// Uso:  node embaralhar_url.js http://seu-endereco:8080/
// O app desembaralha com Segredo.java (mesma chave).
const K = [0x53, 0x6d, 0x57, 0x21, 0x67, 0x30, 0x23, 0x6b, 0x39, 0x14, 0x7e, 0x2c];
const url = process.argv[2];
if (!url) { console.log('Uso: node embaralhar_url.js http://seu-endereco:8080/'); process.exit(1); }
const b = Buffer.from(url, 'utf8');
for (let i = 0; i < b.length; i++) b[i] = (b[i] ^ K[i % K.length] ^ (i * 7)) & 255;
const cod = b.toString('base64');
// confere desembaralhando
const d = Buffer.from(cod, 'base64');
for (let i = 0; i < d.length; i++) d[i] = (d[i] ^ K[i % K.length] ^ (i * 7)) & 255;
if (d.toString('utf8') !== url) { console.log('ERRO: nao conferiu'); process.exit(1); }
console.log(cod);
