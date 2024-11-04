function eval_source(src, env) {
  const code = `(function() {
    var exports = {};
    
    var process = {
      env: ${JSON.stringify(env)}
    };

    function FakeResolvedPromise(result) {
      return {
        value: result,
        then: (f) => {
          const r = f(result);
          if (r && r.then) {
            return r;
          } else {
            return FakeResolvedPromise(r);
          }
        },
        catch: (f) => FakeResolvedPromise(result),
      }
    }

    function FakeRejectedPromise(result) {
      return {
        error: result,
        then: (a, f) => {
          if (f) {
            const r = f(result);
            if (r && r.then) {
              return r;
            } else {
              return FakeResolvedPromise(r);
            }
          } else {
            return FakeRejectedPromise(result);
          }
        },
        catch: (f) => {
          const r = f(result);
          if (r.then) {
            return r;
          } else {
            return FakeResolvedPromise(r);
          }
        }
      }
    }

    const FPromise = {
      resolve: (value) => new FakeResolvedPromise(value),
      reject: (value) => new FakeRejectedPromise(value),
    }

    function fetch(url, _opts) {

      function makeResponse(response) {
        return {
          rawResponse: response,
          status: response.status,
          statusText: 'none',
          headers: response.headers,
          ok: response.status > 199 && response.status < 300,
          redirected: response.status > 299 && response.status < 400,
          clone: () => {
            return makeResponse(response);
          },
          text: () => {
            return FPromise.resolve(response.body);
          },
          json: () => {
            return FPromise.resolve(JSON.parse(response.body));
          },
          blob: () => {
            return FPromise.reject(new Error('unsupported method blob'));
          },
          formData: () => {
            return FPromise.reject(new Error('unsupported method formData'));
          },
          arrayBuffer: () => {
            return FPromise.reject(new Error('unsupported method arrayBuffer'));
          },
          error: () => {
            return FPromise.reject(new Error('unsupported method error'));
          },
          redirected: () => {
            return FPromise.reject(new Error('unsupported method redirected'));
          }
        };
      }

      const opts = _opts || {};

      try {
        let response = {
          status: 0,
          headers: {},
          body: null,
        };
        if (opts.body) {
          const r = Http.request({
            url: url,
            method: opts.method || 'GET',
            headers: opts.headers || {},
          }, opts.body);
          response.status = r.status;
          response.body = r.body;
        } else {
          const r = Http.request({
            url: url,
            method: opts.method || 'GET',
            headers: opts.headers || {},
          });
          response.status = r.status;
          response.body = r.body;
        }
        return FPromise.resolve(makeResponse(response));
      } catch(e) {
        return FPromise.reject(e);
      }
    }
    ${src}
    return exports;
  })();`
  return eval(code);
}


function cloud_apim_module_plugin_execute_tool_call() {
  try {
    const inputStr = Host.inputString();
    const inputJson = JSON.parse(inputStr);
    const code = inputJson.code;
    const arguments = inputJson.arguments;
    const exps = eval_source(code, {});
    const result = exps.tool_call(arguments)
    if (result) {
      if (result.then && result.value) {
        Host.outputString(result.value);
      } else {
        Host.outputString(result);
      }
    } else {
      Host.outputString("no_result_error");
    }
  } catch(e) {
    Host.outputString("caught_error: " + e.message);
  }
}

module.exports = {
  cloud_apim_module_plugin_execute_tool_call: cloud_apim_module_plugin_execute_tool_call,
};