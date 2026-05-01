class OtoroshiAssistant extends Component {
  state = {
    display: false
  }
  render() {
    if (!this.state.display) {
      return (
        React.createElement("div", { className: "otoroshi-assistant", style: { } },
          React.createElement("div", { className: "otoroshi-assistant-button", onClick: () => this.setState({ display: !this.state.display }), style: {
            position: "fixed",
            bottom: 10 + 80,
            right: 10,
            height: 80,
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            backgroundColor: 'black',
            color: 'white',
          } }, "assistant"),
        )
      )
    }
    return (
      React.createElement("div", { className: "otoroshi-assistant", style: { } },
        React.createElement("div", { className: "otoroshi-assistant-chatbox", onClick: () => this.setState({ display: !this.state.display }), style: {
          position: "fixed",
          bottom: 0,
          right: 10,
          height: '70%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'black',
          color: 'white',
        } }, "chat-box"),
      )
    )
  }
}