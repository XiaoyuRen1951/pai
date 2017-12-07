// Copyright (c) Microsoft Corporation
// All rights reserved.
//
// MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
// to permit persons to whom the Software is furnished to do so, subject to the following conditions:
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
// BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.


// module dependencies
const jwt = require('jsonwebtoken');
const authConfig = require('../config/auth');
const authModel = require('../models/auth');
const logger = require('../config/logger');


/**
 * Update.
 */
const update = (req, res) => {
  const username = req.body.username;
  const password = req.body.password;
  authModel.add(username, password, (err, state) => {
    if (err || !state) {
      logger.warn('adding user %s failed', username);
      return res.status(500).json({
        error: 'UpdateFailed',
        message: 'update failed, user exists'
      });
    } else {
      return res.json({
        message: 'update successfully'
      });
    }
  });
}

/**
 * Login.
 */
const login = (req, res) => {
  const username = req.body.username;
  const password = req.body.password;
  authModel.check(username, password, (err, state) => {
    if (err || !state) {
      logger.warn('user %s authentication failed', username);
      return res.status(401).json({
        error: 'AuthenticationFailed',
        message: 'authentication failed'
      });
    } else {
      jwt.sign({
        username: username
      }, authConfig.secret, { expiresIn: '7d' }, (signError, token) => {
        if (signError) {
          logger.warn('sign token error\n%s', signError.stack);
          return res.status(500).json({
            error: 'SignTokenFailed',
            message: 'sign token failed'
          });
        }
        return res.json({
          token,
          user: username
        });
      });
    }
  });
}

// module exports
module.exports = { login, update };