
//import { qrcode } from '../../dist/qrcode.mjs';
import qrcode from '../../dist/qrcode.mjs'; // test import default
import { stringToBytes } from '../../dist/qrcode_UTF8.mjs';
import { utf8 as test } from './qrcode-test-impl.js';

qrcode.stringToBytes = stringToBytes;

test(qrcode);
