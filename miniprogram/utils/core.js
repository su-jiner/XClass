/** 统一入口：页面只需 require('../../utils/core.js') */
const parse = require('./parse.js');
const model = require('./model.js');

module.exports = Object.assign({}, parse, model);
