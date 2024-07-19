import React, { Component } from 'react';

export default class Terminal extends Component {

  state = { copy: false }

  copy = () => {
    this.setState({ copy: true }, () => {
      navigator.clipboard.writeText(this.props.command);
      setTimeout(() => {
        this.setState({ copy: false })
      }, 1000)
    })

  }

  render() {
    const color = this.state.copy ? 'rgb(40, 167, 69)' : 'white';
    let value = (this.props.command || '').trim();
    if (value.startsWith('\n')) {
      value = value.substring(1);
    }
    if (value.startsWith('$ ')) {
      value = value.substring(2);
    }
    return (
      <div className="terminal-component" style={{ width: "100%", marginTop: 20 }}>
        <div style={{ maxWidth: "100%", display: 'flex', flexDirection: 'column', borderRadius: 5 }}>
          <div style={{ borderTopLeftRadius: 5, borderTopRightRadius: 5, height:"2rem", gap:".35rem", display: 'flex', flexDirection: 'row', justifyContent: 'center', alignItems: 'center', background:"#3f52e3", color:"white" }}>
            <div style={{ marginLeft: ".35rem", width: 10, height: 10, borderRadius: "50%", backgroundColor: '#ccc' }}></div>
            <div style={{ width: 10, height: 10, borderRadius: "50%", backgroundColor: '#ccc' }}></div>
            <div style={{ width: 10, height: 10, borderRadius: "50%", backgroundColor: '#ccc' }}></div>
            <span style={{ width: '100%'}}></span>
            <div style={{ cursor: 'pointer', width: 30, height: 30 }} onClick={this.copy}>
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke={color} className="w-5 h-5"><path stroke-linecap="round" stroke-linejoin="round" d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184"></path></svg>
            </div>
          </div>
          <pre style={{ display:"block", overflowX: "auto", background:"#002451", color:"white", padding:"1rem 12px 1rem", borderTopLeftRadius: 0,  borderTopRightRadius: 0, borderBottomRightRadius: 5, borderBottomLeftRadius: 5 }}>
            <code className="language-bash" style={{ whiteSpace: "pre" }}><span>$ {value}</span></code>
            {this.props.result && <code className="language-bash" style={{ whiteSpace: "pre" }}><span>{this.props.result}</span></code>}
          </pre>
        </div>
      </div>
    );
  }
}